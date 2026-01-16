# @Query 어노테이션 지원 설계 문서

> GitHub Issue: #4

## 결정 사항

| 항목 | 결정 |
|------|------|
| 파라미터 바인딩 | Named (`:name`) + Positional (`?1`) 둘 다 |
| 네이티브 쿼리 | `nativeQuery = true` 옵션으로 지원 |
| @Modifying | UPDATE/DELETE 지원, `Int` 반환 (영향받은 행 수) |
| 반환 타입 | `T`, `List<T>`, `Page<T>`, `Slice<T>` |
| @Param | 선택적 (없으면 Kotlin 파라미터 이름 자동 추출) |

## API 형태

```kotlin
interface UserRepository : CoroutineCrudRepository<User, Long> {

    // Named Parameter + @Param
    @Query("SELECT u FROM User u WHERE u.status = :status AND u.createdAt > :since")
    suspend fun findActiveUsersSince(
        @Param("status") status: Status,
        @Param("since") since: LocalDateTime
    ): List<User>

    // Named Parameter (파라미터 이름 자동 추출)
    @Query("SELECT u FROM User u WHERE u.email LIKE :domain")
    suspend fun findByEmailDomain(domain: String): List<User>

    // Positional Parameter
    @Query("SELECT u FROM User u WHERE u.status = ?1 AND u.role = ?2")
    suspend fun findByStatusAndRole(status: Status, role: Role): List<User>

    // 네이티브 쿼리
    @Query(
        value = "SELECT * FROM users WHERE status = ?1",
        nativeQuery = true
    )
    suspend fun findByStatusNative(status: String): List<User>

    // @Modifying (UPDATE)
    @Modifying
    @Query("UPDATE User u SET u.status = :status WHERE u.lastLoginAt < :threshold")
    suspend fun deactivateInactiveUsers(
        @Param("status") status: Status,
        @Param("threshold") threshold: LocalDateTime
    ): Int

    // Page 반환 (countQuery 자동 생성)
    @Query("SELECT u FROM User u WHERE u.status = :status")
    suspend fun findByStatus(status: Status, pageable: Pageable): Page<User>

    // Page 반환 (countQuery 명시)
    @Query(
        value = "SELECT u FROM User u JOIN u.orders o WHERE o.total > :minTotal",
        countQuery = "SELECT COUNT(u) FROM User u JOIN u.orders o WHERE o.total > :minTotal"
    )
    suspend fun findUsersWithLargeOrders(minTotal: BigDecimal, pageable: Pageable): Page<User>
}
```

## 어노테이션 정의

```kotlin
package io.clroot.hibernate.reactive.spring.boot.repository.query

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Query(
    /** JPQL 또는 네이티브 SQL 쿼리 */
    val value: String,
    /** true면 네이티브 SQL로 실행 */
    val nativeQuery: Boolean = false,
    /** Page 반환 시 사용할 COUNT 쿼리 (생략 시 자동 생성) */
    val countQuery: String = "",
)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Modifying

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Param(val value: String)
```

## 구현 컴포넌트

### 변경할 파일

1. **새 파일**: `Query.kt`, `Modifying.kt`, `Param.kt` - 어노테이션 정의
2. **HibernateReactiveRepositoryFactoryBean** - @Query 메서드 감지 및 파싱
3. **PreparedQueryMethod** - @Query 메서드 정보 저장 필드 추가
4. **SimpleHibernateReactiveRepository** - @Query 쿼리 실행 로직

### 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│              HibernateReactiveRepositoryFactoryBean         │
│  1. 메서드에 @Query 있는지 확인                              │
│  2. @Query 있으면 → 직접 쿼리 사용                          │
│  3. @Query 없으면 → 기존 PartTree 파싱                      │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    PreparedQueryMethod                      │
│  - isAnnotatedQuery: Boolean (PartTree vs @Query 구분)      │
│  - isNativeQuery: Boolean                                   │
│  - isModifying: Boolean                                     │
│  - parameterNames: List<String> (Named Parameter용)         │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              SimpleHibernateReactiveRepository              │
│  - executeAnnotatedQuery(): @Query 쿼리 실행                │
│  - bindNamedParameters(): :name 바인딩                      │
│  - bindPositionalParameters(): ?1 바인딩                    │
│  - executeModifyingQuery(): UPDATE/DELETE 실행              │
└─────────────────────────────────────────────────────────────┘
```

## 파라미터 바인딩

### 파라미터 스타일 감지

```kotlin
enum class ParameterStyle { NAMED, POSITIONAL, NONE }

fun detectParameterStyle(query: String): ParameterStyle {
    return when {
        query.contains(Regex(":\\w+")) -> ParameterStyle.NAMED
        query.contains(Regex("\\?\\d+")) -> ParameterStyle.POSITIONAL
        else -> ParameterStyle.NONE
    }
}
```

### Named Parameter 바인딩

```kotlin
// @Param 어노테이션 또는 Kotlin 파라미터 이름으로 매핑
private fun extractParameterNames(method: Method): List<String> {
    return method.parameters
        .filter { it.type != Continuation::class.java }
        .filter { !Pageable::class.java.isAssignableFrom(it.type) }
        .map { param ->
            param.getAnnotation(Param::class.java)?.value ?: param.name
        }
}
```

## 쿼리 실행 로직

### JPQL vs 네이티브

```kotlin
private suspend fun executeAnnotatedQuery(
    prepared: PreparedQueryMethod,
    args: List<Any?>,
    pageable: Pageable?,
): Any? {
    return sessionProvider.read { session ->
        val query = if (prepared.isNativeQuery) {
            session.createNativeQuery(prepared.hql, prepared.entityClass)
        } else {
            session.createQuery(prepared.hql, prepared.entityClass)
        }

        bindParameters(query, prepared, args)

        if (pageable != null) {
            query.firstResult = pageable.offset.toInt()
            query.maxResults = pageable.pageSize
        }

        query.resultList
    }
}
```

### @Modifying 쿼리

```kotlin
private suspend fun executeModifyingQuery(
    prepared: PreparedQueryMethod,
    args: List<Any?>,
): Int {
    return sessionProvider.write { session ->
        val query = if (prepared.isNativeQuery) {
            session.createNativeQuery(prepared.hql)
        } else {
            session.createQuery(prepared.hql)
        }

        bindParameters(query, prepared, args)
        query.executeUpdate()
    }
}
```

## COUNT 쿼리 처리

### 자동 생성

```kotlin
private fun generateCountQuery(query: String): String {
    val normalized = query.trim()

    return if (normalized.startsWith("FROM", ignoreCase = true)) {
        "SELECT COUNT(*) $normalized"
    } else {
        normalized.replaceFirst(
            Regex("SELECT\\s+\\w+\\s+FROM", RegexOption.IGNORE_CASE),
            "SELECT COUNT(*) FROM"
        )
    }
}
```

## 에러 처리

```kotlin
private fun validateQueryMethod(method: Method, queryAnnotation: Query) {
    val query = queryAnnotation.value

    // @Modifying인데 SELECT 쿼리면 에러
    if (method.isAnnotationPresent(Modifying::class.java)) {
        if (query.trim().startsWith("SELECT", ignoreCase = true)) {
            throw IllegalStateException(
                "@Modifying method '${method.name}' cannot have SELECT query"
            )
        }
    }

    // Page 반환인데 Pageable 없으면 에러
    if (isPageReturnType(method) && !hasPageableParameter(method)) {
        throw IllegalStateException(
            "Method '${method.name}' returns Page but has no Pageable parameter"
        )
    }

    // Named/Positional 혼용 금지
    val hasNamed = query.contains(Regex(":\\w+"))
    val hasPositional = query.contains(Regex("\\?\\d+"))
    if (hasNamed && hasPositional) {
        throw IllegalStateException(
            "Method '${method.name}' mixes named and positional parameters"
        )
    }
}
```

## 테스트 케이스

1. JPQL + Named Parameter (`:status` 바인딩)
2. JPQL + Positional Parameter (`?1` 바인딩)
3. @Param 없이 파라미터 이름 자동 추출
4. 네이티브 쿼리 실행 (`nativeQuery = true`)
5. @Modifying UPDATE (영향받은 행 수 반환)
6. @Modifying DELETE (영향받은 행 수 반환)
7. Page 반환 + 자동 COUNT
8. Page 반환 + 명시적 countQuery
9. Slice 반환 (size+1 트릭)
10. 빈 결과 처리
11. 에러: Named/Positional 혼용 (fail-fast)
12. 에러: @Modifying + SELECT (fail-fast)
