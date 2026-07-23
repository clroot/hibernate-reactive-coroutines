# Usage Guide

**[🇰🇷 한국어](usage-guide.ko.md)**

## Configuration

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

### Connection Pool

| Property              | Description                         | Default        |
| --------------------- | ----------------------------------- | -------------- |
| `pool-size`           | Maximum connection pool size        | 10             |
| `connect-timeout`     | Connection acquisition timeout (ms) | Vert.x default |
| `idle-timeout`        | Idle connection timeout (ms)        | Vert.x default |
| `max-wait-queue-size` | Maximum wait queue size             | Vert.x default |

### SSL

| Mode          | Description                              |
| ------------- | ---------------------------------------- |
| `disable`     | No SSL (default)                         |
| `allow`       | Use SSL if server requires it            |
| `prefer`      | Try SSL, fall back to unencrypted        |
| `require`     | SSL required (no certificate validation) |
| `verify-ca`   | SSL + CA certificate validation          |
| `verify-full` | SSL + CA + hostname validation           |

### Repository Scanning

```kotlin
// Default: scans from @SpringBootApplication location
@SpringBootApplication
class MyApplication

// Custom package scanning
@SpringBootApplication
@EnableHibernateReactiveRepositories(basePackages = ["com.example.repository"])
class MyApplication
```

---

## Repository

### CoroutineCrudRepository

Extend `CoroutineCrudRepository` to automatically use CRUD functionality.

```kotlin
interface UserRepository : CoroutineCrudRepository<User, Long>
```

**Available Methods:**

| Method               | Return Type | Description          |
| -------------------- | ----------- | -------------------- |
| `save(entity)`       | `T`         | Save entity          |
| `saveAll(entities)`  | `Flow<T>`   | Save multiple entities |
| `findById(id)`       | `T?`        | Find by ID           |
| `findAll()`          | `Flow<T>`   | Find all             |
| `findAllById(ids)`   | `Flow<T>`   | Find by multiple IDs |
| `count()`            | `Long`      | Count all            |
| `existsById(id)`     | `Boolean`   | Check existence      |
| `deleteById(id)`     | `Unit`      | Delete by ID         |
| `delete(entity)`     | `Unit`      | Delete entity        |
| `deleteAllById(ids)` | `Unit`      | Delete by multiple IDs |
| `deleteAll()`        | `Unit`      | Delete all           |

`save` persists a new entity as the same managed instance, so a generated identifier is visible
on both the argument and the returned value. Existing or detached entities are merged; keep using
the instance returned by `save` for subsequent work. Entities with assigned identifiers can
implement Spring Data's `Persistable` to declare their new-state explicitly.

### Query Method Derivation

Queries are automatically generated based on method names.

```kotlin
interface UserRepository : CoroutineCrudRepository<User, Long> {
    // Single result
    suspend fun findByEmail(email: String): User?
    suspend fun findByNameAndStatus(name: String, status: Status): User?

    // List results
    suspend fun findAllByStatus(status: Status): List<User>
    suspend fun findAllByNameContaining(name: String): List<User>

    // Existence / Count
    suspend fun existsByEmail(email: String): Boolean
    suspend fun countByStatus(status: Status): Long

    // Delete
    suspend fun deleteByEmail(email: String)
}
```

**Supported Keywords:**

| Keyword                       | Example                      | HQL                            |
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

### @Query Annotation

Write JPQL directly for complex queries.

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

`@Modifying` methods must return either `Int` (the affected row count) or `Unit`.
Bulk updates do not synchronize already managed entities. Use
`@Modifying(clearAutomatically = true)` when subsequent reads in the same transaction must
discard the current session cache and observe the database result.

### Pagination

```kotlin
interface UserRepository : CoroutineCrudRepository<User, Long> {
    suspend fun findAll(pageable: Pageable): Page<User>
    suspend fun findAllByStatus(status: Status, pageable: Pageable): Page<User>
    suspend fun findAllByStatus(status: Status, pageable: Pageable): Slice<User>  // No total count
}
```

For `@Query` methods returning `Page`, a count query is derived automatically only for simple
entity queries such as `SELECT u FROM User u ...` or `FROM User u ...`. Declare `countQuery`
explicitly when the query contains projections, joins (including implicit joins), `GROUP BY`,
`HAVING`, set operations, trailing `SELECT`, query-level pagination, or a parameterized
`ORDER BY`. Automatic derivation accepts only simple root-property ordering such as
`ORDER BY u.id DESC`; functions, collection indexing, and path navigation in an order expression
require an explicit `countQuery`. Native page queries always require an explicit `countQuery`.
`Slice` does not run a count query.

Within each annotated query, positional parameters must start at `?1` and remain contiguous, as
required by Hibernate; unlabeled `?` parameters are not supported. Every `countQuery` parameter
must also appear in the content query. PostgreSQL dollar-quoted literals, line comments, and nested
block comments are rejected because their parameter parsing is not consistent across Hibernate's
HQL and native-query parsers.

```kotlin
@Query(
    value = "SELECT u FROM User u LEFT JOIN FETCH u.roles WHERE u.status = :status",
    countQuery = "SELECT COUNT(u) FROM User u WHERE u.status = :status",
)
suspend fun findWithRoles(status: Status, pageable: Pageable): Page<User>
```

**Usage Example:**

```kotlin
val pageable = PageRequest.of(0, 10, Sort.by("createdAt").descending())
val page = userRepository.findAll(pageable)

println("Total elements: ${page.totalElements}")
println("Total pages: ${page.totalPages}")
println("Current page data: ${page.content}")
```

| Type    | Total Count | Use Case              |
| ------- | :---------: | --------------------- |
| `Page`  |      O      | Display total pages   |
| `Slice` |      X      | Infinite scroll, "Load more" |

## Transactions

### @Transactional (Recommended)

Use Spring's `@Transactional` with suspend functions.

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
        // All changes rolled back on exception
    }
}
```

### ReactiveTransactionExecutor

Manage transactions programmatically.

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

Synchronous Lazy Loading is not supported in Hibernate Reactive.

### Option 1: FETCH JOIN (Recommended)

```kotlin
interface ParentRepository : CoroutineCrudRepository<Parent, Long> {
    @Query("SELECT p FROM Parent p LEFT JOIN FETCH p.children WHERE p.id = :id")
    suspend fun findByIdWithChildren(id: Long): Parent?
}
```

### Option 2: fetch() Method

```kotlin
@Transactional(readOnly = true)
suspend fun getChildren(parentId: Long): List<Child> {
    val parent = parentRepository.findById(parentId)!!
    return sessionProvider.fetch(parent, Parent::children)
}
```

### Option 3: fetchAll() - Multiple Associations

```kotlin
@Transactional(readOnly = true)
suspend fun getOrderDetails(orderId: Long): Order {
    val order = orderRepository.findById(orderId)!!
    sessionProvider.fetchAll(order, Order::items, Order::payments)
    return order
}
```

| Method                                            | Use Case                         |
| ------------------------------------------------- | -------------------------------- |
| `fetch(entity, Property::ref)`                    | Load single association          |
| `fetchAll(entity, vararg properties)`             | Load multiple associations       |
| `fetchFromDetached(entity, Class, Property::ref)` | Load association from detached entity |
