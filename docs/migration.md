# Spring Data JPA에서 마이그레이션

## 개요

Spring Data JPA에서 Hibernate Reactive Coroutines로 전환하는 가이드입니다.

## 1. 의존성 변경

```kotlin
// 제거
implementation("org.springframework.boot:spring-boot-starter-data-jpa")

// 추가
implementation("com.github.clroot.hibernate-reactive-coroutines:hibernate-reactive-coroutines-spring-boot-starter:1.4.1")
implementation("io.vertx:vertx-pg-client:4.5.16")  // 또는 MySQL
```

## 2. Repository 인터페이스 수정

### Before (Spring Data JPA)

```kotlin
interface UserRepository : JpaRepository<User, Long> {
    fun findByEmail(email: String): User?
    fun findAllByStatus(status: Status): List<User>
}
```

### After (Hibernate Reactive Coroutines)

```kotlin
interface UserRepository : CoroutineCrudRepository<User, Long> {
    suspend fun findByEmail(email: String): User?
    suspend fun findAllByStatus(status: Status): List<User>
}
```

**변경 사항:**

- `JpaRepository` → `CoroutineCrudRepository`
- 모든 메서드에 `suspend` 키워드 추가

## 3. Service 레이어 수정

### Before

```kotlin
@Service
class UserService(private val userRepository: UserRepository) {

    @Transactional
    fun createUser(name: String): User {
        return userRepository.save(User(name = name))
    }

    @Transactional(readOnly = true)
    fun findUser(id: Long): User? {
        return userRepository.findById(id).orElse(null)
    }
}
```

### After

```kotlin
@Service
class UserService(private val userRepository: UserRepository) {

    @Transactional
    suspend fun createUser(name: String): User {
        return userRepository.save(User(name = name))
    }

    @Transactional(readOnly = true)
    suspend fun findUser(id: Long): User? {
        return userRepository.findById(id)
    }
}
```

**변경 사항:**

- 모든 메서드에 `suspend` 키워드 추가
- `findById().orElse(null)` → `findById()` (nullable 반환)

## 4. Lazy Loading 코드 수정

### Before

```kotlin
@Transactional(readOnly = true)
fun getParentWithChildren(id: Long): Parent {
    val parent = parentRepository.findById(id).orElseThrow()
    parent.children.size  // Lazy Loading 발생
    return parent
}
```

### After - 방법 1: FETCH JOIN (권장)

```kotlin
// Repository
@Query("SELECT p FROM Parent p LEFT JOIN FETCH p.children WHERE p.id = :id")
suspend fun findByIdWithChildren(id: Long): Parent?

// Service
@Transactional(readOnly = true)
suspend fun getParentWithChildren(id: Long): Parent {
    return parentRepository.findByIdWithChildren(id)!!
}
```

### After - 방법 2: fetch() 메서드

```kotlin
@Service
class ParentService(
    private val parentRepository: ParentRepository,
    private val sessionProvider: TransactionalAwareSessionProvider,
) {
    @Transactional(readOnly = true)
    suspend fun getParentWithChildren(id: Long): Parent {
        val parent = parentRepository.findById(id)!!
        sessionProvider.fetch(parent, Parent::children)
        return parent
    }
}
```

## 5. 지원되지 않는 기능 대체

### REQUIRES_NEW

```kotlin
// Before
@Transactional(propagation = Propagation.REQUIRES_NEW)
fun audit(event: AuditEvent) { ... }

// After - 이벤트 기반으로 분리
@EventListener
suspend fun handleAudit(event: AuditEvent) {
    // 별도 트랜잭션에서 처리
}
```

### @EntityGraph

```kotlin
// Before
@EntityGraph(attributePaths = ["children", "address"])
fun findById(id: Long): Parent?

// After - FETCH JOIN 사용
@Query("""
    SELECT p FROM Parent p
    LEFT JOIN FETCH p.children
    LEFT JOIN FETCH p.address
    WHERE p.id = :id
""")
suspend fun findByIdWithDetails(id: Long): Parent?
```

### Native @Modifying

```kotlin
// Before
@Modifying
@Query(value = "UPDATE users SET status = ?1 WHERE id = ?2", nativeQuery = true)
fun updateStatus(status: String, id: Long): Int

// After - JPQL 사용
@Modifying
@Query("UPDATE User u SET u.status = :status WHERE u.id = :id")
suspend fun updateStatus(status: Status, id: Long): Int
```

## 체크리스트

- [ ] 의존성 변경
- [ ] Repository 인터페이스에 `suspend` 추가
- [ ] Service 메서드에 `suspend` 추가
- [ ] Lazy Loading 코드를 FETCH JOIN 또는 `fetch()`로 변경
- [ ] `REQUIRES_NEW` 사용 부분 리팩토링
- [ ] `@EntityGraph` → FETCH JOIN 변경
- [ ] Native @Modifying → JPQL 변경
- [ ] 테스트 코드 업데이트 (runBlocking 또는 runTest 사용)
