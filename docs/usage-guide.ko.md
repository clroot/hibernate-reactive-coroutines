# 사용 가이드

## 설정

### application.yml

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/mydb
    username: user
    password: password
  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect
    hibernate:
      ddl-auto: validate
    show-sql: true
    properties:
      hibernate:
        format_sql: true
        reactive:
          pool-size: 10
          ssl-mode: verify-full
          trust-certificate: /run/secrets/postgres-ca.pem
```

### 커넥션 풀

| 속성                  | 설명                       | 기본값        |
| --------------------- | -------------------------- | ------------- |
| `pool-size`           | 커넥션 풀 최대 크기        | 10            |
| `connect-timeout`     | 커넥션 획득 대기 시간 (ms) | Vert.x 기본값 |
| `idle-timeout`        | 유휴 커넥션 유지 시간 (ms) | Vert.x 기본값 |
| `max-wait-queue-size` | 대기 큐 최대 크기          | Vert.x 기본값 |

### Vert.x 인스턴스

스타터는 Hibernate Reactive가 실행될 Vert.x 인스턴스를 직접 생성해 Spring `Vertx` 빈으로
노출하고, `VertxInstance` 서비스로 주입합니다. 애플리케이션에 `Vertx` 빈을 직접 정의하면
스타터가 물러나 그 빈을 재사용합니다 — Hibernate Reactive가 내부적으로 두 번째 Vert.x를
몰래 띄우는 대신, 앱 전체가 하나의 Vert.x 인스턴스를 공유할 수 있습니다.

`spring.jpa.properties.hibernate.reactive.vertx` 아래 설정 (스타터가 인스턴스를 생성할 때만 적용):

| 속성                            | 설명                                                  | 기본값          |
| ------------------------------- | ----------------------------------------------------- | --------------- |
| `event-loop-pool-size`          | 이벤트 루프 스레드 수                                 | 2 × CPU 코어    |
| `max-event-loop-execute-time`   | blocked-thread checker가 경고하는 루프 점유 시간      | 2s              |
| `blocked-thread-check-interval` | blocked-thread checker 검사 주기                      | 1s              |
| `warning-exception-time`        | 경고에 스택트레이스가 포함되는 루프 점유 시간         | 5s              |

시간 속성은 Spring duration 문법(`500ms`, `2s`)을 지원합니다. `transactional {}` 블록은 이
이벤트 루프에서 실행되므로, 운영 환경에서 `max-event-loop-execute-time`과
`warning-exception-time`을 낮춰두면 실수로 들어간 블로킹 호출(과 그 위치)을 로그에서 빠르게
발견할 수 있습니다.

```yaml
spring:
  jpa:
    properties:
      hibernate:
        reactive:
          vertx:
            event-loop-pool-size: 4
            max-event-loop-execute-time: 500ms
            warning-exception-time: 2s
```

### SSL

| 모드          | 설명                        |
| ------------- | --------------------------- |
| `disable`     | SSL 사용 안함 (기본값)      |
| `allow`       | 서버가 요구하면 SSL 사용    |
| `prefer`      | SSL 시도, 실패 시 비암호화  |
| `require`     | SSL 필수 + 기본 trust store 인증서 검증 |
| `verify-ca`   | SSL + 지정한 CA 인증서 검증             |
| `verify-full` | SSL + 지정한 CA + 호스트명 검증         |

`verify-ca`와 `verify-full`에서는 PEM CA 인증서 경로인 `trust-certificate`를 반드시 지정해야
합니다. `require`에서 별도 인증서를 지정하지 않으면 JVM 기본 trust store를 사용합니다.
잘못된 모드, 필수 인증서 누락, PostgreSQL 클라이언트 클래스 부재, TLS 리플렉션 실패가 있으면
평문 연결로 조용히 전환하지 않고 애플리케이션 시작을 중단합니다.

기존 JDBC URL의 `sslmode`와 `currentSchema` 파라미터도 계속 인식합니다. 두 값을 Hibernate
설정으로 옮긴 뒤 Reactive 연결 URL에서는 제거하므로 PostgreSQL startup 파라미터로 잘못
전달되지 않습니다. 그 밖의 URL 파라미터는 그대로 유지합니다.

### Repository 스캔

```kotlin
// 기본: @SpringBootApplication 위치를 기준으로 스캔
@SpringBootApplication
class MyApplication

// 커스텀 패키지 지정
@SpringBootApplication
@EnableHibernateReactiveRepositories(basePackages = ["com.example.repository"])
class MyApplication
```

---

## Repository

### CoroutineCrudRepository

Spring Data의 `CoroutineCrudRepository`를 상속하여 CRUD 기능을 자동으로 사용할 수 있습니다.

```kotlin
interface UserRepository : CoroutineCrudRepository<User, Long>
```

**제공 메서드:**

| 메서드               | 반환 타입 | 설명             |
| -------------------- | --------- | ---------------- |
| `save(entity)`       | `T`       | 엔티티 저장      |
| `saveAll(entities)`  | `Flow<T>` | 여러 엔티티 저장 |
| `findById(id)`       | `T?`      | ID로 조회        |
| `findAll()`          | `Flow<T>` | 전체 조회        |
| `findAllById(ids)`   | `Flow<T>` | 여러 ID로 조회   |
| `count()`            | `Long`    | 개수 조회        |
| `existsById(id)`     | `Boolean` | 존재 여부        |
| `deleteById(id)`     | `Unit`    | ID로 삭제        |
| `delete(entity)`     | `Unit`    | 엔티티 삭제      |
| `deleteAllById(ids)` | `Unit`    | 여러 ID로 삭제   |
| `deleteAll()`        | `Unit`    | 전체 삭제        |

`save`는 신규 엔티티를 전달받은 동일 인스턴스로 persist하므로 생성된 ID가 인자와 반환값에
모두 반영됩니다. 기존 또는 detached 엔티티는 merge하므로 이후 작업에서는 `save`가 반환한
인스턴스를 사용하세요. 할당형 ID를 쓰는 엔티티는 Spring Data의 `Persistable`을 구현해
신규 상태를 명시할 수 있습니다.

리포지토리 ID 메서드는 Kotlin `@JvmInline` value class도 지원하며 Hibernate에 전달할 때
내부 ID 타입으로 변환합니다.

### 쿼리 메서드 자동 생성

메서드 이름 기반으로 쿼리가 자동 생성됩니다.

```kotlin
interface UserRepository : CoroutineCrudRepository<User, Long> {
    // 단일 조회
    suspend fun findByEmail(email: String): User?
    suspend fun findByNameAndStatus(name: String, status: Status): User?

    // 목록 조회
    suspend fun findAllByStatus(status: Status): List<User>
    suspend fun findAllByNameContaining(name: String): List<User>

    // 존재 확인 / 개수
    suspend fun existsByEmail(email: String): Boolean
    suspend fun countByStatus(status: Status): Long

    // 삭제
    suspend fun deleteByEmail(email: String)
}
```

**지원 키워드:**

| 키워드                        | 예시                         | HQL                            |
| ----------------------------- | ---------------------------- | ------------------------------ |
| `And`                         | `findByNameAndEmail`         | `WHERE name = ? AND email = ?` |
| `Or`                          | `findByNameOrEmail`          | `WHERE name = ? OR email = ?`  |
| `Between`                     | `findByAgeBetween`           | `WHERE age BETWEEN ? AND ?`    |
| `LessThan` / `GreaterThan`    | `findByAgeLessThan`          | `WHERE age < ?`                |
| `Like` / `Containing`         | `findByNameContaining`       | `WHERE name LIKE %?%`          |
| `StartingWith` / `EndingWith` | `findByNameStartingWith`     | `WHERE name LIKE ?%`           |
| `In` / `NotIn`                | `findByStatusIn`             | `WHERE status IN (?)`          |
| `IsNull` / `IsNotNull`        | `findByDeletedAtIsNull`      | `WHERE deletedAt IS NULL`      |
| `True` / `False`              | `findByActiveTrue`           | `WHERE active = TRUE`          |
| `OrderBy`                     | `findByStatusOrderByNameAsc` | `ORDER BY name ASC`            |

### @Query 어노테이션

복잡한 쿼리는 직접 JPQL을 작성할 수 있습니다.

```kotlin
interface UserRepository : CoroutineCrudRepository<User, Long> {
    // Named Parameter
    @Query("SELECT u FROM User u WHERE u.status = :status AND u.role = :role")
    suspend fun findByStatusAndRole(status: Status, role: Role): List<User>

    // Positional Parameter
    @Query("SELECT u FROM User u WHERE u.age BETWEEN ?1 AND ?2")
    suspend fun findByAgeBetween(minAge: Int, maxAge: Int): List<User>

    // UPDATE/DELETE
    @Modifying
    @Query("UPDATE User u SET u.status = :newStatus WHERE u.status = :oldStatus")
    suspend fun updateStatus(oldStatus: Status, newStatus: Status): Int
}
```

`@Modifying` 메서드는 영향받은 행 수를 받는 `Int` 또는 결과를 노출하지 않는 `Unit`을
반환해야 합니다. 벌크 업데이트는 이미 관리 중인 엔티티를 동기화하지 않습니다.
같은 트랜잭션의 후속 조회에서 현재 세션 캐시를 비우고 DB 결과를 읽어야 한다면
`@Modifying(clearAutomatically = true)`를 사용하세요.

### 페이지네이션

```kotlin
interface UserRepository : CoroutineCrudRepository<User, Long> {
    suspend fun findAll(pageable: Pageable): Page<User>
    suspend fun findAllByStatus(status: Status, pageable: Pageable): Page<User>
    suspend fun findAllByStatus(status: Status, pageable: Pageable): Slice<User>  // 총 개수 조회 없음
}
```

`Page`를 반환하는 `@Query` 메서드는 `SELECT u FROM User u ...` 또는 `FROM User u ...`와 같은
단순 엔티티 쿼리만 COUNT 쿼리를 자동 생성합니다. 프로젝션, 조인(암시적 조인 포함),
`GROUP BY`, `HAVING`, 집합 연산, 후행 `SELECT`, 쿼리 자체 페이지 제한, 파라미터가 있는
`ORDER BY`를 사용하면 `countQuery`를 명시해야 합니다. 자동 생성 시에는
`ORDER BY u.id DESC` 같은 단순 루트 속성 정렬만 허용됩니다. 함수, 컬렉션 인덱스,
경로 탐색이 포함된 정렬식은 `countQuery`를 명시해야 합니다. Native Page 쿼리도 항상
`countQuery`가 필요합니다. `Slice`는 COUNT 쿼리를 실행하지 않습니다.

Hibernate의 요구사항에 따라 각 어노테이션 쿼리 안의 위치 파라미터는 `?1`부터 시작해
연속되어야 하며 이름표 없는 `?` 파라미터는 지원하지 않습니다. `countQuery`의 모든
파라미터는 본문 쿼리에도 있어야 합니다. PostgreSQL 달러 인용 리터럴, 한 줄 주석, 중첩
블록 주석은 Hibernate의 HQL 파서와 Native 쿼리 파라미터 파서에서 일관되게 처리되지
않으므로 거부됩니다.

```kotlin
@Query(
    value = "SELECT u FROM User u LEFT JOIN FETCH u.roles WHERE u.status = :status",
    countQuery = "SELECT COUNT(u) FROM User u WHERE u.status = :status",
)
suspend fun findWithRoles(status: Status, pageable: Pageable): Page<User>
```

**사용 예시:**

```kotlin
val pageable = PageRequest.of(0, 10, Sort.by("createdAt").descending())
val page = userRepository.findAll(pageable)

println("총 개수: ${page.totalElements}")
println("총 페이지: ${page.totalPages}")
println("현재 페이지 데이터: ${page.content}")
```

| 타입    | 총 개수 조회 | 용도                  |
| ------- | :----------: | --------------------- |
| `Page`  |      O       | 전체 페이지 수 표시   |
| `Slice` |      X       | 무한 스크롤, "더보기" |

## 트랜잭션

### @Transactional (권장)

Spring의 `@Transactional`을 suspend 함수와 함께 사용합니다.

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

    @Transactional
    suspend fun transfer(fromId: Long, toId: Long, amount: Int) {
        val from = userRepository.findById(fromId)!!
        val to = userRepository.findById(toId)!!

        userRepository.save(from.copy(balance = from.balance - amount))
        userRepository.save(to.copy(balance = to.balance + amount))
        // 예외 발생 시 모든 변경 롤백
    }
}
```

### 트랜잭션 이벤트

starter는 Spring의 reactive `TransactionalEventPublisher`를 자동 구성합니다. Reactive
트랜잭션 안에서는 `ApplicationEventPublisher` 대신 이 publisher를 사용해야
`@TransactionalEventListener`가 요구하는 Reactor 트랜잭션 컨텍스트가 이벤트에 포함됩니다.

```kotlin
@Service
class OrderService(
    private val events: TransactionalEventPublisher,
) {
    @Transactional
    suspend fun placeOrder(command: PlaceOrderCommand) {
        // 주문을 먼저 저장한 뒤...
        events.publishEvent(OrderPlaced(command.orderId)).awaitSingleOrNull()
    }
}

@Component
class OrderPlacedHandler {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun afterCommit(event: OrderPlaced) {
        // 커밋에 성공한 뒤에만 실행됩니다.
    }
}
```

반환되는 `Mono`는 트랜잭션 suspend 함수 안에서 반드시 await해야 합니다. 일반
`ApplicationEventPublisher`로 발행하거나 reactive publisher를 별도로 subscribe하면
reactive 트랜잭션 컨텍스트가 끊겨 after-commit 콜백이 등록되지 않습니다.

### ReactiveTransactionExecutor

프로그래매틱하게 트랜잭션을 관리할 수도 있습니다.

```kotlin
@Service
class OrderService(
    private val tx: ReactiveTransactionExecutor,
    private val orderRepository: OrderRepository,
) {
    suspend fun placeOrder(command: PlaceOrderCommand): Order = tx.transactional {
        orderRepository.save(Order.create(command))
    }

    suspend fun getOrder(id: Long): Order? = tx.readOnly {
        orderRepository.findById(id)
    }
}
```

`transactional`과 `readOnly`는 DB 작업을 필수 Vert.x dispatcher로 옮기면서도 MDC/트레이싱
어댑터와 Reactor context 같은 호출자 코루틴 컨텍스트 요소를 유지합니다.
새로 여는 `readOnly` 세션에서는 Hibernate의 dirty checking과 자동 flush를 비활성화하므로,
조회한 엔티티의 변경이 암묵적으로 DB에 반영되지 않습니다.

## Lazy Loading

Hibernate Reactive에서는 동기적 Lazy Loading이 지원되지 않습니다.

### 방법 1: FETCH JOIN (권장)

```kotlin
interface ParentRepository : CoroutineCrudRepository<Parent, Long> {
    @Query("SELECT p FROM Parent p LEFT JOIN FETCH p.children WHERE p.id = :id")
    suspend fun findByIdWithChildren(id: Long): Parent?
}
```

### 방법 2: fetch() 메서드

```kotlin
@Transactional(readOnly = true)
suspend fun getChildren(parentId: Long): List<Child> {
    val parent = parentRepository.findById(parentId)!!
    return sessionProvider.fetch(parent, Parent::children)
}
```

### 방법 3: fetchAll() - 여러 연관관계

```kotlin
@Transactional(readOnly = true)
suspend fun getOrderDetails(orderId: Long): Order {
    val order = orderRepository.findById(orderId)!!
    sessionProvider.fetchAll(order, Order::items, Order::payments)
    return order
}
```

| 메서드                                            | 용도                          |
| ------------------------------------------------- | ----------------------------- |
| `fetch(entity, Property::ref)`                    | 단일 연관관계 로딩            |
| `fetchAll(entity, vararg properties)`             | 다중 연관관계 로딩            |
| `fetchFromDetached(entity, Class, Property::ref)` | detached 엔티티 연관관계 로딩 |
