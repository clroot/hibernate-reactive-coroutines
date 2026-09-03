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
| `@Query` 스칼라/집계/DTO 반환        |  ✅  | 스칼라와 HQL 생성자 DTO                               |
| `@Query` (Native)                    |  ✅  | 읽기만 지원, Page는 `countQuery` 필요                 |
| `@Modifying`                         |  ✅  | JPQL UPDATE/DELETE, `Int`/`Unit`, 선택적 자동 clear   |
| 페이지네이션 (`Page`, `Slice`)       |  ✅  | 스마트 COUNT 스킵 최적화                              |

`Page`를 반환하는 HQL/JPQL `@Query` 메서드는 단순 엔티티 `SELECT`/`FROM` 쿼리만 COUNT를 자동
생성합니다. 프로젝션, 조인, 그룹화, 집합 연산, 후행 SELECT, 쿼리 자체 페이지 제한,
파라미터가 있는 정렬은 `countQuery`를 명시해야 합니다.

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

아래 항목들은 첫 호출 시점이 아니라 **애플리케이션 기동 시점에** 원인을 설명하는 메시지와 함께 거부됩니다.

| 기능                                 | 대체 방안                                                   |
| ------------------------------------ | ----------------------------------------------------------- |
| Specification (동적 쿼리)            | `@Query`로 직접 작성                                        |
| QueryByExample                       | 조건별 메서드 조합                                          |
| Projection (인터페이스 기반)         | FETCH JOIN 후 Kotlin에서 매핑                               |
| `@EntityGraph`                       | FETCH JOIN 또는 `fetch()` 메서드                            |
| Native `@Modifying`                  | JPQL 사용                                                   |
| `@Query`의 Tuple/배열 반환           | 스칼라 또는 HQL 생성자 DTO 프로젝션 사용                    |
| suspend가 아닌(`Flow` 포함) 쿼리 메서드 | `suspend fun … : List<T>` 로 선언                        |
| 이름과 인자 개수가 같은 오버로드     | 서로 다른 이름 사용                                         |
| `Top`/`First` + `Pageable` 조합      | 둘 중 하나만 사용                                           |
| 네이티브 `@Query` 정렬               | 쿼리 안에 `ORDER BY` 작성                                   |

---

## 동작 상 주의점

위 기능 표 외에, 마이그레이션 전에 알아둘 동작 차이입니다.

**삭제는 조회 후 제거합니다.** `deleteById`, `delete`, `deleteAll`, `deleteAllById`와 파생
`deleteBy…` 메서드는 대상 엔티티를 조회한 뒤 하나씩 제거합니다(`SimpleJpaRepository`와 동일).
따라서 cascade, `@Version` 검사, `@PreRemove` 콜백이 모두 동작하고 영속성 컨텍스트도 일관되게
유지됩니다. 대신 `DELETE` 전에 `SELECT`가 한 번 실행됩니다. cascade 없이 대량 삭제가 필요하면
`@Modifying @Query("DELETE …")`를 명시적으로 작성하세요.

**파생 `deleteBy…`는 삭제 건수를 반환할 수 있습니다.** 반환 타입을 `Unit`, `Int`, `Long` 중 하나로
선언하면 됩니다.

**`LIKE` 값은 이스케이프됩니다.** `Containing`, `StartingWith`, `EndingWith`는 바인딩되는 값의
`%`, `_`, `\`를 이스케이프하므로 `findByNameContaining("%")`는 전체 행이 아니라 퍼센트 문자를
포함한 행만 매칭합니다. 명시적 `Like` 키워드는 사용자가 직접 패턴을 넘기는 것이므로
이스케이프하지 않습니다.

**`IgnoreCase`는 양쪽을 소문자로 비교합니다.** String이 아닌 프로퍼티에 `IgnoreCase`를 쓰면 기동
시점에 거부되고, `AllIgnoreCase`는 String 프로퍼티에만 적용됩니다.

**`Sort`가 `@Query` 메서드에도 적용됩니다.** `Sort` 파라미터나 정렬이 담긴 `Pageable`은 쿼리에
이미 있는 `ORDER BY` 뒤에 덧붙습니다. 즉 쿼리에 작성한 정렬이 우선합니다.

**엔티티 이름은 JPA 메타모델에서 가져옵니다.** `@Entity(name = "…")`으로 이름을 바꾼 엔티티나
패키지가 다른 동명 엔티티도 정상 동작합니다.

**`Flow`는 스트리밍이 아닙니다.** `findAll()` 등 `Flow`를 반환하는 메서드는 전체 결과를 메모리에
적재한 뒤 방출합니다. 큰 테이블에는 `Pageable`을 사용하세요.

**`@Transactional`과 `tx.transactional {}`을 섞어 써도 안전합니다.** `tx.transactional {}`은 활성
Spring 트랜잭션이 있으면 새 세션을 열지 않고 그 트랜잭션에 참여하며,
`@Transactional(readOnly = true)` 트랜잭션을 쓰기로 승격하려 하면 예외를 던집니다.

---

## 마이그레이션 단계

### 1. 의존성 변경

```kotlin
// 제거
implementation("org.springframework.boot:spring-boot-starter-data-jpa")

// 추가
implementation("io.clroot:hibernate-reactive-coroutines-spring-boot-starter:1.2.1")
implementation("io.vertx:vertx-pg-client:5.1.5")  // 또는 MySQL
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
