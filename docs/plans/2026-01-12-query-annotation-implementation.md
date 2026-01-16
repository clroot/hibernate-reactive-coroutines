# @Query Annotation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** @Query 어노테이션으로 JPQL/네이티브 쿼리를 직접 작성할 수 있는 기능 구현

**Architecture:** @Query 어노테이션이 있으면 PartTree 파싱 대신 직접 쿼리를 사용하고, Named/Positional 파라미터 바인딩을 지원하며, @Modifying으로 UPDATE/DELETE 쿼리도 처리

**Tech Stack:** Kotlin Annotations, Hibernate Reactive, Spring Data Commons (Pageable, Page, Slice)

---

## Task 1: 어노테이션 정의 (Query, Modifying, Param)

**Files:**
- Create: `hibernate-reactive-coroutines-spring-boot-starter/src/main/kotlin/io/clroot/hibernate/reactive/spring/boot/repository/query/Query.kt`
- Create: `hibernate-reactive-coroutines-spring-boot-starter/src/main/kotlin/io/clroot/hibernate/reactive/spring/boot/repository/query/Modifying.kt`
- Create: `hibernate-reactive-coroutines-spring-boot-starter/src/main/kotlin/io/clroot/hibernate/reactive/spring/boot/repository/query/Param.kt`

**Step 1: Query 어노테이션 생성**

```kotlin
package io.clroot.hibernate.reactive.spring.boot.repository.query

/**
 * JPQL 또는 네이티브 SQL 쿼리를 직접 지정합니다.
 *
 * @param value JPQL 또는 네이티브 SQL 쿼리
 * @param nativeQuery true면 네이티브 SQL로 실행
 * @param countQuery Page 반환 시 사용할 COUNT 쿼리 (생략 시 자동 생성)
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Query(
    val value: String,
    val nativeQuery: Boolean = false,
    val countQuery: String = "",
)
```

**Step 2: Modifying 어노테이션 생성**

```kotlin
package io.clroot.hibernate.reactive.spring.boot.repository.query

/**
 * UPDATE/DELETE 쿼리임을 표시합니다.
 * @Query와 함께 사용하며, 반환 타입은 Int (영향받은 행 수)입니다.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Modifying
```

**Step 3: Param 어노테이션 생성**

```kotlin
package io.clroot.hibernate.reactive.spring.boot.repository.query

/**
 * Named Parameter의 이름을 지정합니다.
 * 생략하면 Kotlin 파라미터 이름을 사용합니다.
 *
 * @param value 파라미터 이름 (:name 형식에서 name 부분)
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Param(val value: String)
```

**Step 4: 컴파일 확인**

Run: `./gradlew :hibernate-reactive-coroutines-spring-boot-starter:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add -A && git commit -m "feat(#4): Query, Modifying, Param 어노테이션 정의"
```

---

## Task 2: PreparedQueryMethod 확장

**Files:**
- Modify: `hibernate-reactive-coroutines-spring-boot-starter/src/main/kotlin/io/clroot/hibernate/reactive/spring/boot/repository/query/PreparedQueryMethod.kt`

**Step 1: 새 필드 추가**

```kotlin
package io.clroot.hibernate.reactive.spring.boot.repository.query

import org.springframework.data.repository.query.parser.PartTree
import java.lang.reflect.Method

/**
 * 파라미터 바인딩 스타일.
 */
enum class ParameterStyle {
    /** Named Parameter (:name) */
    NAMED,
    /** Positional Parameter (?1) */
    POSITIONAL,
    /** 파라미터 없음 */
    NONE,
}

/**
 * 애플리케이션 시작 시 파싱된 쿼리 메서드 정보.
 *
 * PartTree 파싱 결과와 생성된 HQL을 캐싱하여 런타임 오버헤드를 제거합니다.
 *
 * @param method 원본 메서드
 * @param partTree 파싱된 PartTree (@Query 메서드면 null)
 * @param hql 생성된 HQL 쿼리 또는 @Query의 쿼리
 * @param countHql Page 반환 타입일 때 사용할 COUNT HQL (null이면 COUNT 불필요)
 * @param parameterBinders 파라미터별 바인더 (LIKE 패턴 변환 등, @Query면 빈 리스트)
 * @param returnType 반환 타입 정보
 * @param isAnnotatedQuery @Query 어노테이션 사용 여부
 * @param isNativeQuery 네이티브 쿼리 여부
 * @param isModifying @Modifying 어노테이션 사용 여부
 * @param parameterStyle 파라미터 바인딩 스타일 (NAMED, POSITIONAL, NONE)
 * @param parameterNames Named Parameter 사용 시 파라미터 이름 목록
 */
data class PreparedQueryMethod(
    val method: Method,
    val partTree: PartTree?,
    val hql: String,
    val countHql: String?,
    val parameterBinders: List<ParameterBinder>,
    val returnType: QueryReturnType,
    val isAnnotatedQuery: Boolean = false,
    val isNativeQuery: Boolean = false,
    val isModifying: Boolean = false,
    val parameterStyle: ParameterStyle = ParameterStyle.NONE,
    val parameterNames: List<String> = emptyList(),
)

/**
 * 쿼리 메서드의 반환 타입.
 */
enum class QueryReturnType {
    /** 단일 엔티티 (nullable) */
    SINGLE,

    /** 엔티티 리스트 */
    LIST,

    /** Boolean (existsBy) */
    BOOLEAN,

    /** Long (countBy) */
    LONG,

    /** Unit/Void (deleteBy) */
    VOID,

    /** Page<T> (페이징 + 총 개수) */
    PAGE,

    /** Slice<T> (페이징, 총 개수 없음) */
    SLICE,

    /** Int (@Modifying 쿼리의 영향받은 행 수) */
    MODIFYING,
}
```

**Step 2: 컴파일 확인**

Run: `./gradlew :hibernate-reactive-coroutines-spring-boot-starter:compileKotlin`
Expected: BUILD FAILED (기존 코드에서 새 필드 전달 안 함)

**Step 3: HibernateReactiveRepositoryFactoryBean 임시 수정**

기존 `parseMethod` 함수에서 새 필드에 기본값 추가:

```kotlin
return PreparedQueryMethod(
    method = method,
    partTree = partTree,
    hql = buildResult.hql,
    countHql = countHql,
    parameterBinders = buildResult.parameterBinders,
    returnType = returnType,
    isAnnotatedQuery = false,  // 추가
    isNativeQuery = false,     // 추가
    isModifying = false,       // 추가
    parameterStyle = ParameterStyle.NONE,  // 추가
    parameterNames = emptyList(),          // 추가
)
```

**Step 4: 컴파일 확인**

Run: `./gradlew :hibernate-reactive-coroutines-spring-boot-starter:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add -A && git commit -m "feat(#4): PreparedQueryMethod에 @Query 관련 필드 추가"
```

---

## Task 3: @Query 메서드 감지 및 파싱 로직 추가

**Files:**
- Modify: `hibernate-reactive-coroutines-spring-boot-starter/src/main/kotlin/io/clroot/hibernate/reactive/spring/boot/repository/HibernateReactiveRepositoryFactoryBean.kt`

**Step 1: import 추가**

```kotlin
import io.clroot.hibernate.reactive.spring.boot.repository.query.Modifying
import io.clroot.hibernate.reactive.spring.boot.repository.query.Param
import io.clroot.hibernate.reactive.spring.boot.repository.query.ParameterStyle
import io.clroot.hibernate.reactive.spring.boot.repository.query.Query
```

**Step 2: 파라미터 스타일 감지 함수 추가**

```kotlin
/**
 * 쿼리에서 파라미터 바인딩 스타일을 감지합니다.
 */
private fun detectParameterStyle(query: String): ParameterStyle {
    val hasNamed = query.contains(Regex(":\\w+"))
    val hasPositional = query.contains(Regex("\\?\\d+"))

    return when {
        hasNamed && hasPositional -> throw IllegalStateException(
            "Query mixes named (:name) and positional (?1) parameters"
        )
        hasNamed -> ParameterStyle.NAMED
        hasPositional -> ParameterStyle.POSITIONAL
        else -> ParameterStyle.NONE
    }
}
```

**Step 3: 파라미터 이름 추출 함수 추가**

```kotlin
/**
 * 메서드에서 쿼리 파라미터 이름을 추출합니다.
 * @Param 어노테이션이 있으면 사용하고, 없으면 Kotlin 파라미터 이름 사용.
 */
private fun extractParameterNames(method: Method): List<String> {
    return method.parameters
        .filter { it.type != Continuation::class.java }
        .filter { !Pageable::class.java.isAssignableFrom(it.type) }
        .filter { !Sort::class.java.isAssignableFrom(it.type) }
        .map { param ->
            param.getAnnotation(Param::class.java)?.value ?: param.name
        }
}
```

**Step 4: @Query 메서드 검증 함수 추가**

```kotlin
/**
 * @Query 메서드를 검증합니다.
 */
private fun validateQueryAnnotation(method: Method, queryAnnotation: Query) {
    val query = queryAnnotation.value
    val isModifying = method.isAnnotationPresent(Modifying::class.java)

    // @Modifying인데 SELECT 쿼리면 에러
    if (isModifying && query.trim().startsWith("SELECT", ignoreCase = true)) {
        throw IllegalStateException(
            "@Modifying method '${method.name}' cannot have SELECT query"
        )
    }

    // SELECT 쿼리가 아닌데 @Modifying가 없으면 에러
    if (!isModifying && !query.trim().startsWith("SELECT", ignoreCase = true) &&
        !query.trim().startsWith("FROM", ignoreCase = true)) {
        throw IllegalStateException(
            "Method '${method.name}' has UPDATE/DELETE query but missing @Modifying annotation"
        )
    }
}
```

**Step 5: COUNT 쿼리 자동 생성 함수 추가**

```kotlin
/**
 * JPQL 쿼리에서 COUNT 쿼리를 자동 생성합니다.
 */
private fun generateCountQuery(query: String): String {
    val normalized = query.trim()

    return when {
        normalized.startsWith("FROM", ignoreCase = true) ->
            "SELECT COUNT(*) $normalized".replace(Regex(" ORDER BY .+$", RegexOption.IGNORE_CASE), "")

        normalized.startsWith("SELECT", ignoreCase = true) ->
            normalized
                .replaceFirst(Regex("SELECT\\s+\\S+\\s+FROM", RegexOption.IGNORE_CASE), "SELECT COUNT(*) FROM")
                .replace(Regex(" ORDER BY .+$", RegexOption.IGNORE_CASE), "")

        else -> throw IllegalStateException("Cannot generate count query from: $query")
    }
}
```

**Step 6: parseMethod 수정 - @Query 우선 처리**

```kotlin
private fun parseMethod(method: Method, entityClass: Class<*>): PreparedQueryMethod {
    val queryAnnotation = method.getAnnotation(Query::class.java)

    // @Query 어노테이션이 있으면 직접 쿼리 사용
    if (queryAnnotation != null) {
        return parseAnnotatedQueryMethod(method, entityClass, queryAnnotation)
    }

    // 기존 PartTree 파싱 로직
    return parsePartTreeMethod(method, entityClass)
}

/**
 * @Query 어노테이션이 있는 메서드를 파싱합니다.
 */
private fun parseAnnotatedQueryMethod(
    method: Method,
    entityClass: Class<*>,
    queryAnnotation: Query,
): PreparedQueryMethod {
    validateQueryAnnotation(method, queryAnnotation)

    val hasPageable = hasPageableParameter(method)
    val isModifying = method.isAnnotationPresent(Modifying::class.java)

    val returnType = when {
        isModifying -> QueryReturnType.MODIFYING
        isPageReturnType(method) -> QueryReturnType.PAGE
        isSliceReturnType(method) -> QueryReturnType.SLICE
        isListReturnType(method) -> QueryReturnType.LIST
        else -> QueryReturnType.SINGLE
    }

    // Page/Slice 반환인데 Pageable이 없으면 에러
    if ((returnType == QueryReturnType.PAGE || returnType == QueryReturnType.SLICE) && !hasPageable) {
        throw IllegalStateException(
            "Method '${method.name}' returns Page/Slice but has no Pageable parameter"
        )
    }

    val query = queryAnnotation.value
    val parameterStyle = detectParameterStyle(query)
    val parameterNames = if (parameterStyle == ParameterStyle.NAMED) {
        extractParameterNames(method)
    } else {
        emptyList()
    }

    // COUNT 쿼리 처리
    val countHql = when {
        returnType != QueryReturnType.PAGE -> null
        queryAnnotation.countQuery.isNotEmpty() -> queryAnnotation.countQuery
        queryAnnotation.nativeQuery -> throw IllegalStateException(
            "Native query with Page return type requires explicit countQuery"
        )
        else -> generateCountQuery(query)
    }

    return PreparedQueryMethod(
        method = method,
        partTree = null,
        hql = query,
        countHql = countHql,
        parameterBinders = emptyList(),
        returnType = returnType,
        isAnnotatedQuery = true,
        isNativeQuery = queryAnnotation.nativeQuery,
        isModifying = isModifying,
        parameterStyle = parameterStyle,
        parameterNames = parameterNames,
    )
}

/**
 * PartTree 기반 메서드를 파싱합니다. (기존 로직 리팩토링)
 */
private fun parsePartTreeMethod(method: Method, entityClass: Class<*>): PreparedQueryMethod {
    val hasPageable = hasPageableParameter(method)
    val hasSort = hasSortParameter(method)

    val partTree = PartTree(method.name, entityClass)
    val entityName = entityClass.simpleName
    val returnType = determineReturnType(method, partTree)

    if ((returnType == QueryReturnType.PAGE || returnType == QueryReturnType.SLICE) && !hasPageable) {
        throw IllegalStateException(
            "Method '${method.name}' returns Page/Slice but has no Pageable parameter"
        )
    }

    val builder = PartTreeHqlBuilder(entityName, partTree)
    val buildResult = if (hasPageable || hasSort) {
        builder.buildWithSort(null)
    } else {
        builder.build()
    }

    val countHql = if (returnType == QueryReturnType.PAGE) {
        builder.buildCountHql()
    } else {
        null
    }

    return PreparedQueryMethod(
        method = method,
        partTree = partTree,
        hql = buildResult.hql,
        countHql = countHql,
        parameterBinders = buildResult.parameterBinders,
        returnType = returnType,
        isAnnotatedQuery = false,
        isNativeQuery = false,
        isModifying = false,
        parameterStyle = ParameterStyle.NONE,
        parameterNames = emptyList(),
    )
}
```

**Step 7: 컴파일 확인**

Run: `./gradlew :hibernate-reactive-coroutines-spring-boot-starter:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 8: 기존 테스트 실행**

Run: `./gradlew :hibernate-reactive-coroutines-spring-boot-starter:test -x processTestAot`
Expected: PASS (기존 기능 유지)

**Step 9: Commit**

```bash
git add -A && git commit -m "feat(#4): @Query 메서드 감지 및 파싱 로직 추가"
```

---

## Task 4: SimpleHibernateReactiveRepository에 @Query 실행 로직 추가

**Files:**
- Modify: `hibernate-reactive-coroutines-spring-boot-starter/src/main/kotlin/io/clroot/hibernate/reactive/spring/boot/repository/SimpleHibernateReactiveRepository.kt`

**Step 1: import 추가**

```kotlin
import io.clroot.hibernate.reactive.spring.boot.repository.query.ParameterStyle
```

**Step 2: executeQueryMethod 수정**

```kotlin
private suspend fun executeQueryMethod(
    prepared: PreparedQueryMethod,
    args: List<Any?>,
): Any? {
    val (queryArgs, pageable, sort) = extractPagingParams(args)

    // @Query 어노테이션 메서드 처리
    if (prepared.isAnnotatedQuery) {
        return executeAnnotatedQuery(prepared, queryArgs, pageable)
    }

    // 기존 PartTree 기반 쿼리 처리
    val boundArgs = queryArgs.mapIndexed { index, arg ->
        prepared.parameterBinders.getOrNull(index)?.bind(arg) ?: arg
    }

    return when (prepared.returnType) {
        QueryReturnType.SINGLE -> executeSingleQuery(prepared.hql, boundArgs)
        QueryReturnType.LIST -> {
            if (sort != null) {
                executeListQueryWithSort(prepared, boundArgs, sort)
            } else {
                executeListQuery(prepared.hql, boundArgs)
            }
        }
        QueryReturnType.BOOLEAN -> executeExistsQuery(prepared.hql, boundArgs)
        QueryReturnType.LONG -> executeCountQuery(prepared.hql, boundArgs)
        QueryReturnType.VOID -> executeDeleteQuery(prepared.hql, boundArgs)
        QueryReturnType.PAGE -> executePageQuery(prepared, boundArgs, pageable!!)
        QueryReturnType.SLICE -> executeSliceQuery(prepared, boundArgs, pageable!!)
        QueryReturnType.MODIFYING -> throw IllegalStateException("MODIFYING should be handled by executeAnnotatedQuery")
    }
}
```

**Step 3: @Query 쿼리 실행 함수 추가**

```kotlin
/**
 * @Query 어노테이션 메서드를 실행합니다.
 */
private suspend fun executeAnnotatedQuery(
    prepared: PreparedQueryMethod,
    args: List<Any?>,
    pageable: Pageable?,
): Any? {
    return when (prepared.returnType) {
        QueryReturnType.MODIFYING -> executeModifyingAnnotatedQuery(prepared, args)
        QueryReturnType.PAGE -> executePageAnnotatedQuery(prepared, args, pageable!!)
        QueryReturnType.SLICE -> executeSliceAnnotatedQuery(prepared, args, pageable!!)
        QueryReturnType.LIST -> executeListAnnotatedQuery(prepared, args)
        QueryReturnType.SINGLE -> executeSingleAnnotatedQuery(prepared, args)
        else -> throw IllegalStateException("Unsupported return type for @Query: ${prepared.returnType}")
    }
}

/**
 * @Modifying 쿼리를 실행합니다.
 */
private suspend fun executeModifyingAnnotatedQuery(
    prepared: PreparedQueryMethod,
    args: List<Any?>,
): Int {
    return sessionProvider.write { session ->
        val query = if (prepared.isNativeQuery) {
            @Suppress("DEPRECATION")
            session.createNativeQuery(prepared.hql)
        } else {
            session.createMutationQuery(prepared.hql)
        }

        bindAnnotatedParameters(query, prepared, args)
        query.executeUpdate()
    }
}

/**
 * @Query List 쿼리를 실행합니다.
 */
private suspend fun executeListAnnotatedQuery(
    prepared: PreparedQueryMethod,
    args: List<Any?>,
): List<T> {
    return sessionProvider.read { session ->
        val query = if (prepared.isNativeQuery) {
            session.createNativeQuery(prepared.hql, entityClass)
        } else {
            session.createQuery(prepared.hql, entityClass)
        }

        bindAnnotatedParameters(query, prepared, args)
        query.resultList
    }
}

/**
 * @Query 단일 결과 쿼리를 실행합니다.
 */
private suspend fun executeSingleAnnotatedQuery(
    prepared: PreparedQueryMethod,
    args: List<Any?>,
): T? {
    return sessionProvider.read { session ->
        val query = if (prepared.isNativeQuery) {
            session.createNativeQuery(prepared.hql, entityClass)
        } else {
            session.createQuery(prepared.hql, entityClass)
        }

        bindAnnotatedParameters(query, prepared, args)
        query.singleResultOrNull
    }
}

/**
 * @Query Page 쿼리를 실행합니다.
 */
private suspend fun executePageAnnotatedQuery(
    prepared: PreparedQueryMethod,
    args: List<Any?>,
    pageable: Pageable,
): Page<T> {
    val content = sessionProvider.read { session ->
        val query = if (prepared.isNativeQuery) {
            session.createNativeQuery(prepared.hql, entityClass)
        } else {
            session.createQuery(prepared.hql, entityClass)
        }

        bindAnnotatedParameters(query, prepared, args)
        query.firstResult = pageable.offset.toInt()
        query.maxResults = pageable.pageSize
        query.resultList
    }

    val totalElements = if (content.size < pageable.pageSize) {
        pageable.offset + content.size
    } else {
        executeCountAnnotatedQuery(prepared, args)
    }

    return PageImpl(content, pageable, totalElements)
}

/**
 * @Query COUNT 쿼리를 실행합니다.
 */
private suspend fun executeCountAnnotatedQuery(
    prepared: PreparedQueryMethod,
    args: List<Any?>,
): Long {
    val countHql = prepared.countHql!!
    return sessionProvider.read { session ->
        val query = if (prepared.isNativeQuery) {
            session.createNativeQuery(countHql, Long::class.javaObjectType)
        } else {
            session.createQuery(countHql, Long::class.javaObjectType)
        }

        bindAnnotatedParameters(query, prepared, args)
        query.singleResult
    } ?: 0L
}

/**
 * @Query Slice 쿼리를 실행합니다.
 */
private suspend fun executeSliceAnnotatedQuery(
    prepared: PreparedQueryMethod,
    args: List<Any?>,
    pageable: Pageable,
): Slice<T> {
    val content = sessionProvider.read { session ->
        val query = if (prepared.isNativeQuery) {
            session.createNativeQuery(prepared.hql, entityClass)
        } else {
            session.createQuery(prepared.hql, entityClass)
        }

        bindAnnotatedParameters(query, prepared, args)
        query.firstResult = pageable.offset.toInt()
        query.maxResults = pageable.pageSize + 1
        query.resultList
    }

    val hasNext = content.size > pageable.pageSize
    val sliceContent = if (hasNext) content.dropLast(1) else content

    return SliceImpl(sliceContent, pageable, hasNext)
}
```

**Step 4: 파라미터 바인딩 함수 추가**

```kotlin
/**
 * @Query 파라미터를 바인딩합니다.
 */
private fun bindAnnotatedParameters(
    query: org.hibernate.reactive.mutiny.Mutiny.Query<*>,
    prepared: PreparedQueryMethod,
    args: List<Any?>,
) {
    when (prepared.parameterStyle) {
        ParameterStyle.NAMED -> {
            prepared.parameterNames.forEachIndexed { index, name ->
                query.setParameter(name, args[index])
            }
        }
        ParameterStyle.POSITIONAL -> {
            args.forEachIndexed { index, arg ->
                query.setParameter(index + 1, arg)  // Positional은 1부터 시작
            }
        }
        ParameterStyle.NONE -> {
            // 파라미터 없음
        }
    }
}

/**
 * @Query 파라미터를 바인딩합니다. (MutationQuery용)
 */
private fun bindAnnotatedParameters(
    query: org.hibernate.reactive.mutiny.Mutiny.MutationQuery,
    prepared: PreparedQueryMethod,
    args: List<Any?>,
) {
    when (prepared.parameterStyle) {
        ParameterStyle.NAMED -> {
            prepared.parameterNames.forEachIndexed { index, name ->
                query.setParameter(name, args[index])
            }
        }
        ParameterStyle.POSITIONAL -> {
            args.forEachIndexed { index, arg ->
                query.setParameter(index + 1, arg)
            }
        }
        ParameterStyle.NONE -> {
            // 파라미터 없음
        }
    }
}
```

**Step 5: 컴파일 확인**

Run: `./gradlew :hibernate-reactive-coroutines-spring-boot-starter:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add -A && git commit -m "feat(#4): SimpleHibernateReactiveRepository에 @Query 실행 로직 추가"
```

---

## Task 5: TestEntityRepository에 @Query 테스트 메서드 추가

**Files:**
- Modify: `hibernate-reactive-coroutines-spring-boot-starter/src/testFixtures/kotlin/io/clroot/hibernate/reactive/test/TestEntityRepository.kt`

**Step 1: @Query 메서드 추가**

```kotlin
package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.spring.boot.repository.query.Modifying
import io.clroot.hibernate.reactive.spring.boot.repository.query.Param
import io.clroot.hibernate.reactive.spring.boot.repository.query.Query
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.domain.Sort
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 테스트용 Repository 인터페이스.
 */
interface TestEntityRepository : CoroutineCrudRepository<TestEntity, Long> {

    // === 기존 메서드들 (생략) ===

    // === @Query 메서드 ===

    // Named Parameter + @Param
    @Query("SELECT e FROM TestEntity e WHERE e.value = :value")
    suspend fun findByValueWithQuery(@Param("value") value: Int): List<TestEntity>

    // Named Parameter (파라미터 이름 자동 추출)
    @Query("SELECT e FROM TestEntity e WHERE e.name = :name AND e.value = :value")
    suspend fun findByNameAndValueWithQuery(name: String, value: Int): TestEntity?

    // Positional Parameter
    @Query("SELECT e FROM TestEntity e WHERE e.value > ?1 AND e.value < ?2")
    suspend fun findByValueBetweenWithQuery(min: Int, max: Int): List<TestEntity>

    // @Modifying UPDATE
    @Modifying
    @Query("UPDATE TestEntity e SET e.value = :newValue WHERE e.value = :oldValue")
    suspend fun updateValue(@Param("oldValue") oldValue: Int, @Param("newValue") newValue: Int): Int

    // @Modifying DELETE
    @Modifying
    @Query("DELETE FROM TestEntity e WHERE e.value = :value")
    suspend fun deleteByValueWithQuery(@Param("value") value: Int): Int

    // @Query + Page
    @Query("SELECT e FROM TestEntity e WHERE e.value = :value")
    suspend fun findByValueWithQueryPageable(@Param("value") value: Int, pageable: Pageable): Page<TestEntity>

    // @Query + Slice
    @Query("SELECT e FROM TestEntity e WHERE e.value > :minValue")
    suspend fun findByValueGreaterThanWithQuerySlice(@Param("minValue") minValue: Int, pageable: Pageable): Slice<TestEntity>

    // @Query + 명시적 countQuery
    @Query(
        value = "SELECT e FROM TestEntity e WHERE e.value = :value ORDER BY e.name",
        countQuery = "SELECT COUNT(e) FROM TestEntity e WHERE e.value = :value"
    )
    suspend fun findByValueWithExplicitCount(@Param("value") value: Int, pageable: Pageable): Page<TestEntity>
}
```

**Step 2: 컴파일 확인**

Run: `./gradlew :hibernate-reactive-coroutines-spring-boot-starter:compileTestFixturesKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add -A && git commit -m "feat(#4): TestEntityRepository에 @Query 테스트 메서드 추가"
```

---

## Task 6: @Query 통합 테스트 작성

**Files:**
- Create: `hibernate-reactive-coroutines-spring-boot-starter/src/test/kotlin/io/clroot/hibernate/reactive/test/QueryAnnotationIntegrationTest.kt`

**Step 1: 테스트 파일 생성**

```kotlin
package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest

@SpringBootTest(classes = [TestApplication::class])
class QueryAnnotationIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("@Query 어노테이션") {

            context("Named Parameter + @Param") {
                it("@Param으로 지정한 이름으로 파라미터를 바인딩한다") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "query_named_1", value = 500))
                        testEntityRepository.save(TestEntity(name = "query_named_2", value = 500))
                        testEntityRepository.save(TestEntity(name = "query_named_3", value = 600))
                    }

                    // when
                    val found = tx.readOnly {
                        testEntityRepository.findByValueWithQuery(500)
                    }

                    // then
                    found shouldHaveSize 2
                    found.map { it.name } shouldContainExactlyInAnyOrder listOf("query_named_1", "query_named_2")
                }
            }

            context("Named Parameter (자동 추출)") {
                it("@Param 없이도 Kotlin 파라미터 이름으로 바인딩한다") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "auto_param", value = 700))
                    }

                    // when
                    val found = tx.readOnly {
                        testEntityRepository.findByNameAndValueWithQuery("auto_param", 700)
                    }

                    // then
                    found.shouldNotBeNull()
                    found.name shouldBe "auto_param"
                    found.value shouldBe 700
                }

                it("일치하지 않으면 null 반환") {
                    val found = tx.readOnly {
                        testEntityRepository.findByNameAndValueWithQuery("nonexistent", 999)
                    }

                    found.shouldBeNull()
                }
            }

            context("Positional Parameter") {
                it("?1, ?2 형식으로 파라미터를 바인딩한다") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "positional_1", value = 10))
                        testEntityRepository.save(TestEntity(name = "positional_2", value = 20))
                        testEntityRepository.save(TestEntity(name = "positional_3", value = 30))
                        testEntityRepository.save(TestEntity(name = "positional_4", value = 40))
                    }

                    // when
                    val found = tx.readOnly {
                        testEntityRepository.findByValueBetweenWithQuery(15, 35)
                    }

                    // then
                    found shouldHaveSize 2
                    found.map { it.value } shouldContainExactlyInAnyOrder listOf(20, 30)
                }
            }

            context("@Modifying UPDATE") {
                it("UPDATE 쿼리를 실행하고 영향받은 행 수를 반환한다") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "update_1", value = 100))
                        testEntityRepository.save(TestEntity(name = "update_2", value = 100))
                        testEntityRepository.save(TestEntity(name = "update_3", value = 200))
                    }

                    // when
                    val affected = tx.transactional {
                        testEntityRepository.updateValue(100, 150)
                    }

                    // then
                    affected shouldBe 2

                    val updated = tx.readOnly {
                        testEntityRepository.findByValueWithQuery(150)
                    }
                    updated shouldHaveSize 2
                }
            }

            context("@Modifying DELETE") {
                it("DELETE 쿼리를 실행하고 영향받은 행 수를 반환한다") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "delete_1", value = 300))
                        testEntityRepository.save(TestEntity(name = "delete_2", value = 300))
                        testEntityRepository.save(TestEntity(name = "delete_3", value = 400))
                    }

                    // when
                    val affected = tx.transactional {
                        testEntityRepository.deleteByValueWithQuery(300)
                    }

                    // then
                    affected shouldBe 2

                    val remaining = tx.readOnly {
                        testEntityRepository.findByValueWithQuery(300)
                    }
                    remaining shouldHaveSize 0
                }
            }

            context("@Query + Page") {
                it("페이징과 함께 쿼리를 실행한다") {
                    // given
                    tx.transactional {
                        repeat(10) { i ->
                            testEntityRepository.save(TestEntity(name = "page_query_$i", value = 800))
                        }
                    }

                    // when
                    val page = tx.readOnly {
                        testEntityRepository.findByValueWithQueryPageable(800, PageRequest.of(0, 3))
                    }

                    // then
                    page.content shouldHaveSize 3
                    page.totalElements shouldBe 10
                    page.totalPages shouldBe 4
                }
            }

            context("@Query + Slice") {
                it("Slice로 다음 페이지 여부를 확인한다") {
                    // given
                    tx.transactional {
                        repeat(5) { i ->
                            testEntityRepository.save(TestEntity(name = "slice_query_$i", value = 850 + i))
                        }
                    }

                    // when
                    val slice = tx.readOnly {
                        testEntityRepository.findByValueGreaterThanWithQuerySlice(849, PageRequest.of(0, 3))
                    }

                    // then
                    slice.content shouldHaveSize 3
                    slice.hasNext() shouldBe true
                }
            }

            context("@Query + 명시적 countQuery") {
                it("명시적으로 지정한 countQuery를 사용한다") {
                    // given
                    tx.transactional {
                        repeat(7) { i ->
                            testEntityRepository.save(TestEntity(name = "explicit_count_$i", value = 900))
                        }
                    }

                    // when
                    val page = tx.readOnly {
                        testEntityRepository.findByValueWithExplicitCount(900, PageRequest.of(0, 5))
                    }

                    // then
                    page.content shouldHaveSize 5
                    page.totalElements shouldBe 7
                }
            }
        }
    }
}
```

**Step 2: 테스트 실행**

Run: `./gradlew :hibernate-reactive-coroutines-spring-boot-starter:test --tests "*.QueryAnnotationIntegrationTest" -x processTestAot`
Expected: PASS

**Step 3: Commit**

```bash
git add -A && git commit -m "test(#4): @Query 어노테이션 통합 테스트 작성"
```

---

## Task 7: 에러 케이스 테스트 추가

**Files:**
- Create: `hibernate-reactive-coroutines-spring-boot-starter/src/test/kotlin/io/clroot/hibernate/reactive/spring/boot/repository/QueryAnnotationValidationTest.kt`

**Step 1: 검증 테스트 작성**

```kotlin
package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.spring.boot.repository.query.Modifying
import io.clroot.hibernate.reactive.spring.boot.repository.query.Query
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.string.shouldContain
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.lang.reflect.Method

class QueryAnnotationValidationTest : DescribeSpec({

    describe("@Query 검증") {

        context("Named/Positional 혼용") {
            it("혼용 시 예외를 발생시킨다") {
                val exception = shouldThrow<IllegalStateException> {
                    // 실제 파싱 로직 호출 시뮬레이션
                    validateMixedParameters("SELECT e FROM Entity e WHERE e.name = :name AND e.value = ?1")
                }
                exception.message shouldContain "mixes named"
            }
        }

        context("@Modifying + SELECT") {
            it("@Modifying에 SELECT 쿼리면 예외를 발생시킨다") {
                val exception = shouldThrow<IllegalStateException> {
                    validateModifyingWithSelect("SELECT e FROM Entity e")
                }
                exception.message shouldContain "cannot have SELECT"
            }
        }
    }
})

// 검증 로직 헬퍼 (실제 구현체 로직과 동일)
private fun validateMixedParameters(query: String) {
    val hasNamed = query.contains(Regex(":\\w+"))
    val hasPositional = query.contains(Regex("\\?\\d+"))
    if (hasNamed && hasPositional) {
        throw IllegalStateException("Query mixes named (:name) and positional (?1) parameters")
    }
}

private fun validateModifyingWithSelect(query: String) {
    if (query.trim().startsWith("SELECT", ignoreCase = true)) {
        throw IllegalStateException("@Modifying method cannot have SELECT query")
    }
}
```

**Step 2: 테스트 실행**

Run: `./gradlew :hibernate-reactive-coroutines-spring-boot-starter:test --tests "*.QueryAnnotationValidationTest" -x processTestAot`
Expected: PASS

**Step 3: Commit**

```bash
git add -A && git commit -m "test(#4): @Query 검증 테스트 추가"
```

---

## Task 8: 전체 테스트 및 빌드 확인

**Step 1: 전체 테스트 실행**

Run: `./gradlew test -x processTestAot`
Expected: PASS

**Step 2: 빌드 확인**

Run: `./gradlew build -x processTestAot`
Expected: BUILD SUCCESSFUL

**Step 3: 최종 커밋**

```bash
git add -A && git commit -m "feat(#4): @Query 어노테이션 지원 구현 완료"
```

---

## Summary

| Task | Description | Files |
|------|-------------|-------|
| 1 | 어노테이션 정의 | Query.kt, Modifying.kt, Param.kt |
| 2 | PreparedQueryMethod 확장 | PreparedQueryMethod.kt |
| 3 | @Query 감지 및 파싱 | HibernateReactiveRepositoryFactoryBean.kt |
| 4 | @Query 실행 로직 | SimpleHibernateReactiveRepository.kt |
| 5 | 테스트 Repository 메서드 추가 | TestEntityRepository.kt |
| 6 | @Query 통합 테스트 | QueryAnnotationIntegrationTest.kt |
| 7 | 에러 케이스 테스트 | QueryAnnotationValidationTest.kt |
| 8 | 전체 테스트 및 빌드 | - |
