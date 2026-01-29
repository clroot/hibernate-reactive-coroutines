# Spring Data JPA에서 마이그레이션

Spring Data JPA에서 Hibernate Reactive Coroutines로 전환하는 가이드입니다.

---

## JPA 기능 커버리지

**전체 커버리지: ~85-90%** - 핵심 기능은 모두 지원됩니다.

### Repository 기능

| 기능                                 | 지원 | 비고                                                  |
| ------------------------------------ | :--: | ----------------------------------------------------- |
| `CrudRepository` 메서드              |  ✅  | save, findById, findAll, delete, count, existsById 등 |
| `findBy*` 쿼리 메서드                |  ✅  | PartTree 기반 자동 생성                               |
| `countBy*`, `existsBy*`, `deleteBy*` |  ✅  |                                                       |
| LIKE 검색                            |  ✅  | Containing, StartingWith, EndingWith                  |
| 비교 연산                            |  ✅  | GreaterThan, LessThan, Between 등                     |
| `@Query` (JPQL)                      |  ✅  | Named/Positional Parameter                            |
| `@Query` (Native)                    |  ✅  | 읽기만, countQuery 명시 필요                          |
| `@Modifying`                         |  ✅  | JPQL UPDATE/DELETE                                    |
| 페이지네이션 (`Page`, `Slice`)       |  ✅  | 스마트 COUNT 스킵 최적화                              |

### 트랜잭션

| 기능                     | 지원 | 비고                           |
| ------------------------ | :--: | ------------------------------ |
| `@Transactional`         |  ✅  | suspend 함수 지원              |
| readOnly / timeout       |  ✅  |                                |
| Propagation.REQUIRED     |  ✅  | 기본값                         |
| Propagation.REQUIRES_NEW |  ⚠️  | 커넥션 풀 고갈 위험, 중첩 제한 |
| Programmatic Transaction |  ✅  | ReactiveTransactionExecutor    |

### JPA 동작

| 기능                       | 지원 | 비고                       |
| -------------------------- | :--: | -------------------------- |
| Dirty Checking             |  ✅  | 커밋 시 자동 저장          |
| First-level Cache          |  ✅  | 트랜잭션 내 동일 인스턴스  |
| Optimistic Locking         |  ✅  | `@Version`                 |
| Entity Lifecycle Callbacks |  ✅  | @PrePersist, @PreUpdate 등 |
| Lazy Loading               |  ✅  | `fetch()` 메서드 사용      |
| Pessimistic Locking        |  ❌  |                            |

### 미지원 기능

| 기능                         | 대체 방안                        |
| ---------------------------- | -------------------------------- |
| Specification (동적 쿼리)    | `@Query`로 직접 작성             |
| QueryByExample               | 조건별 메서드 조합               |
| Projection (인터페이스 기반) | `SELECT new DTO(...)` 사용       |
| `@EntityGraph`               | FETCH JOIN 또는 `fetch()` 메서드 |
| Native @Modifying            | JPQL 사용                        |

---

## 마이그레이션 단계

### 1. 의존성 변경

```kotlin
// 제거
implementation("org.springframework.boot:spring-boot-starter-data-jpa")

// 추가
implementation("io.clroot:hibernate-reactive-coroutines-spring-boot-starter:1.0.0")
implementation("io.vertx:vertx-pg-client:4.5.16")  // 또는 MySQL
```

### 2. Repository 인터페이스 수정

```kotlin
// Before (Spring Data JPA)
interface UserRepository : JpaRepository<User, Long> {
    fun findByEmail(email: String): User?
}

// After (Hibernate Reactive Coroutines)
interface UserRepository : CoroutineCrudRepository<User, Long> {
    suspend fun findByEmail(email: String): User?
}
```

**변경:** `JpaRepository` → `CoroutineCrudRepository`, 모든 메서드에 `suspend` 추가

### 3. Service 레이어 수정

```kotlin
// Before
@Transactional
fun createUser(name: String): User {
    return userRepository.save(User(name = name))
}

// After
@Transactional
suspend fun createUser(name: String): User {
    return userRepository.save(User(name = name))
}
```

**변경:** `suspend` 추가, `findById().orElse(null)` → `findById()` (nullable 반환)

### 4. Lazy Loading 변환

```kotlin
// Before - Hibernate Reactive에서 작동하지 않음
parent.children.size  // HR000069 에러

// After - 방법 1: FETCH JOIN (권장)
@Query("SELECT p FROM Parent p LEFT JOIN FETCH p.children WHERE p.id = :id")
suspend fun findByIdWithChildren(id: Long): Parent?

// After - 방법 2: fetch() 메서드
sessionProvider.fetch(parent, Parent::children)
```

### 5. 미지원 기능 대체

**@EntityGraph → FETCH JOIN:**
```kotlin
// Before
@EntityGraph(attributePaths = ["children", "address"])
fun findById(id: Long): Parent?

// After
@Query("""
    SELECT p FROM Parent p
    LEFT JOIN FETCH p.children
    LEFT JOIN FETCH p.address
    WHERE p.id = :id
""")
suspend fun findByIdWithDetails(id: Long): Parent?
```

**REQUIRES_NEW → 이벤트 기반:**
```kotlin
// Before
@Transactional(propagation = Propagation.REQUIRES_NEW)
fun audit(event: AuditEvent) { ... }

// After
@EventListener
suspend fun handleAudit(event: AuditEvent) { ... }
```

**Native @Modifying → JPQL:**
```kotlin
// Before
@Query(value = "UPDATE users SET status = ?1", nativeQuery = true)
fun updateStatus(status: String): Int

// After
@Query("UPDATE User u SET u.status = :status")
suspend fun updateStatus(status: Status): Int
```

---

## 체크리스트

- [ ] 의존성 변경
- [ ] Repository 인터페이스에 `suspend` 추가
- [ ] Service 메서드에 `suspend` 추가
- [ ] Lazy Loading → FETCH JOIN 또는 `fetch()` 변환
- [ ] `@EntityGraph` → FETCH JOIN 변환
- [ ] `REQUIRES_NEW` → 이벤트 기반 변환
- [ ] Native @Modifying → JPQL 변환
- [ ] 테스트 업데이트 (`runBlocking` 또는 `runTest` 사용)
