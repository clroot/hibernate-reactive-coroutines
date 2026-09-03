# Spring Data JPA에서 마이그레이션

[1장](#1-호환-범위)에서 사용 중인 기능이 지원되는지 확인하고, [2장](#2-마이그레이션-단계) 순서대로
코드를 바꾼 뒤, [3장](#3-동작-차이)에서 결과가 달라질 수 있는 동작을 점검하시면 됩니다.

## 1. 호환 범위

✅는 그대로 동작, ⚠️는 제한 있음, ❌는 미지원입니다.

### 1.1 리포지토리

| 기능 | 지원 | 비고 |
| --- | :---: | --- |
| `CrudRepository` 메서드 | ✅ | |
| `findBy*` 파생 쿼리 | ✅ | |
| `countBy*`, `existsBy*`, `deleteBy*` | ✅ | `deleteBy*`는 삭제 건수를 `Int`나 `Long`으로 받을 수 있습니다. |
| LIKE 검색, 비교 연산 | ✅ | `Containing`, `StartingWith`, `Between` 등 |
| `@Query` (JPQL) | ✅ | 이름·위치 파라미터 |
| `@Query` 스칼라·집계·DTO 반환 | ✅ | HQL 생성자 표현식 |
| `@Query` (Native) | ⚠️ | 읽기만 지원하며 `Page` 반환 시 `countQuery` 필수 |
| `@Modifying` | ⚠️ | JPQL만 지원 |
| 페이지네이션 (`Page`, `Slice`), `Sort` | ✅ | |
| Specification, Query by Example | ❌ | `@Query`로 작성 |
| 인터페이스 프로젝션, `Tuple`·배열 반환 | ❌ | 생성자 DTO 사용 |
| `@EntityGraph` | ❌ | FETCH JOIN 또는 `fetch()` |

### 1.2 트랜잭션

| 기능 | 지원 | 비고 |
| --- | :---: | --- |
| `@Transactional`, `readOnly`, `timeout` | ✅ | PostgreSQL에서는 `timeout`이 `statement_timeout`으로도 적용됩니다. |
| `Propagation.REQUIRED` | ✅ | 기본값 |
| `Propagation.REQUIRES_NEW` | ⚠️ | 단독 호출은 동작하지만 중첩 호출은 커넥션 풀 고갈 위험이 있어 지원하지 않습니다. |
| 프로그래밍 방식 트랜잭션 | ✅ | `ReactiveTransactionExecutor` |
| `@TransactionalEventListener` | ✅ | reactive `TransactionalEventPublisher`로 발행해야 합니다. |

### 1.3 JPA 동작

| 기능 | 지원 | 비고 |
| --- | :---: | --- |
| Dirty Checking, 1차 캐시 | ✅ | |
| 낙관적 락 (`@Version`) | ✅ | |
| 엔티티 생명주기 콜백 | ✅ | `@PrePersist`, `@PreUpdate` 등 |
| Auditing | ✅ | `@CreatedDate`, `@LastModifiedDate`, `@CreatedBy`, `@LastModifiedBy` |
| Lazy Loading | ⚠️ | 동기 방식 불가. FETCH JOIN 또는 `fetch()` |
| 비관적 락 | ❌ | |

### 1.4 기동 시점 검증

미지원 기능과 잘못된 리포지토리 선언은 첫 호출이 아니라 **기동 시점에** 거부됩니다. 마이그레이션 뒤
애플리케이션을 한 번 띄우면 남은 문제를 대부분 찾을 수 있습니다.

| 거부되는 선언 | 대체 방법 |
| --- | --- |
| `suspend`가 아닌 쿼리 메서드 (`Flow` 반환 포함) | `suspend fun ...: List<T>` |
| 이름과 인자 개수가 같은 오버로드 | 다른 이름 사용 |
| `Top`/`First`와 `Pageable`의 조합 | 둘 중 하나만 사용 |
| Native `@Query`에 `Sort` 또는 정렬이 담긴 `Pageable` | 쿼리 안에 `ORDER BY` 작성 |
| Native `@Modifying` | JPQL로 작성 |
| String이 아닌 프로퍼티의 `IgnoreCase` | 키워드 제거 |
| COUNT를 자동 생성할 수 없는 `Page` 반환 `@Query` | `countQuery` 지정 |

## 2. 마이그레이션 단계

### 2.1 의존성 변경

Spring Boot 3에서는 `spring-boot-starter-data-jpa`를 반드시 제거합니다. Spring Framework 6가
Hibernate ORM 7을 지원하지 않아 두 스타터가 공존하면 기동에 실패합니다. Spring Boot 4에서는 공존할 수
있습니다.

```kotlin
val hrcVersion = "2.0.0"

dependencies {
    // 제거
    // implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // 추가
    implementation("io.clroot:hibernate-reactive-coroutines-spring-boot-starter:$hrcVersion")
    // implementation("io.clroot:hibernate-reactive-coroutines-spring-boot-starter-boot4:$hrcVersion")
    runtimeOnly("io.vertx:vertx-pg-client:5.1.5") // MySQL은 vertx-mysql-client
}
```

`spring.datasource`와 `spring.jpa` 설정은 그대로 둡니다.

### 2.2 리포지토리 인터페이스 수정

상속 인터페이스를 바꾸고, 모든 메서드에 `suspend`를 붙이고, 쿼리 어노테이션의 import를 바꿉니다.

```kotlin
// 변경 전
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface UserRepository : JpaRepository<User, Long> {
    fun findByEmail(email: String): User?

    @Query("SELECT u FROM User u WHERE u.status = :status")
    fun findByStatus(status: Status): List<User>
}

// 변경 후
import io.clroot.hibernate.reactive.repository.query.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface UserRepository : CoroutineCrudRepository<User, Long> {
    suspend fun findByEmail(email: String): User?

    @Query("SELECT u FROM User u WHERE u.status = :status")
    suspend fun findByStatus(status: Status): List<User>
}
```

`flush()`, `saveAndFlush()`, `getReferenceById()`는 없습니다. flush는 트랜잭션 종료 시 자동으로
일어나고, 참조 프록시는 `findById()`로 대체합니다.

### 2.3 서비스 계층 수정

리포지토리를 호출하는 메서드에 `suspend`를 붙입니다. `findById()`가 `Optional` 대신 nullable을
반환하므로 `orElseThrow`를 `?:`로 바꿉니다. 컨트롤러까지 `suspend`로 이어져야 합니다.

```kotlin
// 변경 전
@Transactional
fun rename(id: Long, name: String): User {
    val user = userRepository.findById(id).orElseThrow { NotFoundException() }
    user.name = name
    return user
}

// 변경 후
@Transactional
suspend fun rename(id: Long, name: String): User {
    val user = userRepository.findById(id) ?: throw NotFoundException()
    user.name = name
    return user
}
```

### 2.4 Lazy Loading 변환

연관관계에 접근하기만 하면 로딩되던 코드는 모두 바꿔야 합니다. 초기화되지 않은 연관관계에 접근하면
`HR000069` 오류가 발생합니다.

```kotlin
// 변경 전
val parent = parentRepository.findById(id).orElseThrow()
parent.children.size

// 변경 후 (권장): FETCH JOIN
@Query("SELECT p FROM Parent p LEFT JOIN FETCH p.children WHERE p.id = :id")
suspend fun findByIdWithChildren(id: Long): Parent?

// 변경 후 (대안): fetch()
val parent = parentRepository.findById(id) ?: throw NotFoundException()
sessionProvider.fetch(parent, Parent::children)
```

자세한 내용은 [사용 가이드 5장](usage-guide.ko.md#5-연관관계-로딩)을 참고하세요.

### 2.5 미지원 기능 대체

**`@EntityGraph` → FETCH JOIN**

```kotlin
// 변경 전
@EntityGraph(attributePaths = ["children", "address"])
fun findById(id: Long): Parent?

// 변경 후
@Query("""
    SELECT p FROM Parent p
    LEFT JOIN FETCH p.children
    LEFT JOIN FETCH p.address
    WHERE p.id = :id
""")
suspend fun findByIdWithDetails(id: Long): Parent?
```

**`REQUIRES_NEW` → 트랜잭션 이벤트.** 부모와 무관하게 커밋되어야 하는 작업은 커밋 후 실행되는
리스너로 옮깁니다.

```kotlin
// 변경 전
@Transactional(propagation = Propagation.REQUIRES_NEW)
fun audit(event: AuditEvent) { ... }

// 변경 후
@Transactional
suspend fun placeOrder(command: PlaceOrderCommand) {
    orders.save(Order.create(command))
    events.publishEvent(OrderPlaced(command.orderId)).awaitSingleOrNull()
}

@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
fun audit(event: OrderPlaced) { ... }
```

발행 조건은 [사용 가이드 4.4절](usage-guide.ko.md#44-트랜잭션-이벤트-spring-boot-전용)에 있습니다.

**Native `@Modifying` → JPQL**

```kotlin
// 변경 전
@Modifying
@Query(value = "UPDATE users SET status = ?1", nativeQuery = true)
fun updateStatus(status: String): Int

// 변경 후
@Modifying
@Query("UPDATE User u SET u.status = :status")
suspend fun updateStatus(status: Status): Int
```

**Specification, Query by Example → `@Query`.** 조건 조합이 많다면 nullable 파라미터를 받는
JPQL을 작성합니다.

```kotlin
@Query("""
    SELECT u FROM User u
    WHERE (:status IS NULL OR u.status = :status)
      AND (:name IS NULL OR u.name LIKE CONCAT('%', :name, '%'))
""")
suspend fun search(status: Status?, name: String?, pageable: Pageable): Page<User>
```

### 2.6 테스트 수정

테스트는 `runTest` 또는 `runBlocking` 안에서 실행합니다. 트랜잭션 안에 블로킹 호출이 남아 있는지
확인하려면 [사용 가이드 6장](usage-guide.ko.md#6-블로킹-호출-탐지)의 BlockHound 모듈을 추가하세요.

## 3. 동작 차이

기능 표에는 없지만 결과가 달라질 수 있는 동작입니다.

| 항목 | Spring Data JPA | 이 라이브러리 |
| --- | --- | --- |
| `findAll()` 반환 타입 | `List<T>` | `Flow<T>`. 스트리밍이 아니라 전체 결과를 메모리에 올린 뒤 방출합니다. |
| `Containing` 등의 와일드카드 | `%`, `_` 이스케이프 | `\`도 이스케이프합니다. `Like`는 이스케이프하지 않습니다. |
| `IgnoreCase` | 방언에 따름 | 양쪽을 `LOWER()`로 비교합니다. String 프로퍼티 전용입니다. |
| `@Query`와 `Sort`의 조합 | `ORDER BY` 뒤에 덧붙임 | 같습니다. Native 쿼리에는 `Sort`를 줄 수 없습니다. |
| `@Transactional` 안의 프로그래밍 방식 트랜잭션 | 기존 트랜잭션에 참여 | 같습니다. 단, `readOnly = true` 안에서 쓰기 트랜잭션을 열면 예외가 발생합니다. |
| 트랜잭션 안의 블로킹 호출 | 허용 | 이벤트 루프를 멈추므로 금지합니다. |

삭제 방식(조회 후 제거), `save()` 반환값, 벌크 `@Modifying` 이후의 세션 처리는 Spring Data JPA와
같습니다.

## 4. 체크리스트

- [ ] `spring-boot-starter-data-jpa`를 제거하고 스타터와 Vert.x 클라이언트를 추가했다.
- [ ] 리포지토리가 `CoroutineCrudRepository`를 상속하고 모든 메서드에 `suspend`가 붙어 있다.
- [ ] `@Query`, `@Param`, `@Modifying`의 import를 바꿨다.
- [ ] 서비스와 컨트롤러에 `suspend`를 붙이고 `Optional` 처리를 nullable 처리로 바꿨다.
- [ ] 연관관계 접근을 FETCH JOIN 또는 `fetch()`로 바꿨다.
- [ ] `@EntityGraph`, `REQUIRES_NEW`, Native `@Modifying`, Specification을 대체했다.
- [ ] 테스트를 `runTest`로 감쌌고 BlockHound를 추가했다.
- [ ] 애플리케이션을 기동해 리포지토리 검증 오류가 없음을 확인했다.
