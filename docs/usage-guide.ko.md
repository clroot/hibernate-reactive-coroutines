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
          ssl-mode: disable
```

### 커넥션 풀

| 속성                  | 설명                       | 기본값        |
| --------------------- | -------------------------- | ------------- |
| `pool-size`           | 커넥션 풀 최대 크기        | 10            |
| `connect-timeout`     | 커넥션 획득 대기 시간 (ms) | Vert.x 기본값 |
| `idle-timeout`        | 유휴 커넥션 유지 시간 (ms) | Vert.x 기본값 |
| `max-wait-queue-size` | 대기 큐 최대 크기          | Vert.x 기본값 |

### SSL

| 모드          | 설명                        |
| ------------- | --------------------------- |
| `disable`     | SSL 사용 안함 (기본값)      |
| `allow`       | 서버가 요구하면 SSL 사용    |
| `prefer`      | SSL 시도, 실패 시 비암호화  |
| `require`     | SSL 필수 (인증서 검증 안함) |
| `verify-ca`   | SSL + CA 인증서 검증        |
| `verify-full` | SSL + CA + 호스트명 검증    |

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

### 페이지네이션

```kotlin
interface UserRepository : CoroutineCrudRepository<User, Long> {
    suspend fun findAll(pageable: Pageable): Page<User>
    suspend fun findAllByStatus(status: Status, pageable: Pageable): Page<User>
    suspend fun findAllByStatus(status: Status, pageable: Pageable): Slice<User>  // 총 개수 조회 없음
}
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
