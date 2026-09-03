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
          ssl-mode: verify-full
          trust-certificate: /run/secrets/postgres-ca.pem
```

### Connection Pool

| Property              | Description                         | Default        |
| --------------------- | ----------------------------------- | -------------- |
| `pool-size`           | Maximum connection pool size        | 10             |
| `connect-timeout`     | Connection acquisition timeout (ms) | Vert.x default |
| `idle-timeout`        | Idle connection timeout (ms)        | Vert.x default |
| `max-wait-queue-size` | Maximum wait queue size             | Vert.x default |

### Vert.x Instance

The starter creates the Vert.x instance Hibernate Reactive runs on, exposes it as a Spring `Vertx`
bean, and injects it via the `VertxInstance` service. Define your own `Vertx` bean and the starter
backs off and reuses yours — so the whole application can share a single Vert.x instance instead of
Hibernate Reactive silently spinning up a second one.

Settings under `spring.jpa.properties.hibernate.reactive.vertx` (applied only when the starter
creates the instance):

| Property                       | Description                                              | Default        |
| ------------------------------ | -------------------------------------------------------- | -------------- |
| `event-loop-pool-size`         | Number of event loop threads                             | 2 × CPU cores  |
| `max-event-loop-execute-time`  | Loop occupancy before the blocked-thread checker warns   | 2s             |
| `blocked-thread-check-interval`| How often the blocked-thread checker runs                | 1s             |
| `warning-exception-time`       | Loop occupancy before warnings include a stack trace     | 5s             |

Duration properties accept Spring's duration syntax (`500ms`, `2s`). `transactional {}` blocks run
on these event loops, so lowering `max-event-loop-execute-time` and `warning-exception-time` in
production surfaces accidental blocking calls (and who made them) in the logs quickly.

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

### Event Loop Sharing (opt-in)

In a WebFlux application, reactor-netty and Vert.x each run their own Netty event loop pool by
default. Setting `share-event-loops: true` runs the embedded Netty reactive web server on the
starter's Vert.x event loops instead — one thread pool for HTTP serving and DB I/O:

```yaml
spring:
  jpa:
    properties:
      hibernate:
        reactive:
          vertx:
            share-event-loops: true
```

Requests then start on Vert.x event loop threads, so entering `transactional {}` no longer hops to
a different pool, and both the Vert.x blocked-thread checker and the
`hibernate-reactive-coroutines-blockhound` integration cover the web layer too. Works on Spring
Boot 3.x and 4.x; a user-defined `ReactorResourceFactory` bean takes precedence.

**Understand the trade-off before enabling.** Separate pools act as a bulkhead: a blocking call
inside a `transactional {}` block stalls only the DB layer. With shared loops, that same mistake
freezes every HTTP connection assigned to that loop — health checks included — and heavy DB event
traffic competes with HTTP events on the same threads. Verify your code with BlockHound in tests
and tighten the blocked-thread checker in production before turning this on. This is why it is
opt-in.

### SSL

| Mode          | Description                              |
| ------------- | ---------------------------------------- |
| `disable`     | No SSL (default)                         |
| `allow`       | Use SSL if server requires it            |
| `prefer`      | Try SSL, fall back to unencrypted        |
| `require`     | SSL required + default trust store validation |
| `verify-ca`   | SSL + configured CA certificate validation    |
| `verify-full` | SSL + configured CA + hostname validation     |

`verify-ca` and `verify-full` require `trust-certificate`, which must point to a PEM CA certificate.
`require` uses the JVM default trust store when no custom certificate is configured. Invalid modes,
missing required certificates, unavailable PostgreSQL client classes, and TLS reflection failures
stop startup instead of silently falling back to plaintext.

Legacy JDBC URL parameters `sslmode` and `currentSchema` are still recognized. After they are
translated to Hibernate settings, they are removed from the Reactive connection URL so PostgreSQL
does not receive them as startup parameters. Other URL parameters are preserved.

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

### Jakarta Data contract for non-Spring integrations

The framework-neutral repository module exposes a coroutine contract that extends Jakarta Data's
`DataRepository` marker without introducing synchronous/blocking CRUD methods:

```kotlin
import io.clroot.hibernate.reactive.repository.CoroutineCrudRepository
import io.clroot.hibernate.reactive.repository.query.QueryOptions
import jakarta.data.Order
import jakarta.data.Sort
import jakarta.data.page.Page
import jakarta.data.page.PageRequest
import jakarta.data.repository.Param
import jakarta.data.repository.Query

interface UserRepository : CoroutineCrudRepository<User, Long> {
    suspend fun findByEmail(email: String): User?

    @Query("where status = :status")
    suspend fun findByStatus(
        @Param("status") status: Status,
        pageRequest: PageRequest,
        order: Order<User>,
    ): Page<User>

    @Query("UPDATE User u SET u.status = 'INACTIVE' WHERE u.id = :id")
    suspend fun deactivate(@Param("id") id: Long): Int

    suspend fun findByNameContaining(name: String, sort: Sort<User>): List<User>
}
```

`JakartaDataRepositoryFactory` parses Jakarta Data `@Query` and `@Param`, `Page`/`PageRequest`,
`Sort`, and `Order` into the same neutral descriptors used by Spring repositories. Update and delete
statements are inferred from the query text and may return `Unit`, `Int`, or `Long`. HRC method-name
query derivation remains available as a coroutine-specific extension.

Jakarta Data 1.0 has no metadata for a native query, explicit page count query, or clearing the
persistence context. When one of these existing HRC capabilities is needed, add `@QueryOptions`
alongside Jakarta Data `@Query`:

```kotlin
@Query("SELECT * FROM users WHERE status = :status")
@QueryOptions(
    nativeQuery = true,
    countQuery = "SELECT COUNT(*) FROM users WHERE status = :status",
)
suspend fun findNative(status: String, pageRequest: PageRequest): Page<User>
```

Current compatibility boundary:

- Offset `PageRequest` is supported; cursor requests and `CursoredPage` are not yet supported.
- `PageRequest.withoutTotal()` avoids the count query, and `Page.totalElements()`/`totalPages()` then
  throw as required by Jakarta Data.
- This is not a complete Jakarta Data provider. Lifecycle annotations, parameter-based `@Find`,
  `Limit`, and the synchronous built-in repository interfaces are outside the current scope.
- No Spring Framework or Spring Data dependency is required by the repository module.

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

Repository ID methods also accept Kotlin `@JvmInline` value classes and unwrap them to the
underlying Hibernate identifier type.

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

Scalar, aggregate, and HQL constructor DTO projections use the declared return type:

```kotlin
data class UserSummary(val name: String, val age: Int)

@Query("SELECT COUNT(u) FROM User u WHERE u.status = :status")
suspend fun countByStatus(status: Status): Long

@Query("SELECT u.name FROM User u ORDER BY u.name")
suspend fun findNames(): List<String>

@Query("SELECT new com.example.UserSummary(u.name, u.age) FROM User u ORDER BY u.name")
suspend fun findSummaries(): List<UserSummary>
```

Interface-based and Tuple/array projections are not supported. Use a concrete constructor DTO instead.

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

#### Transaction timeouts

A finite `@Transactional(timeout = ...)` is enforced as a transaction deadline. On PostgreSQL, the
starter also installs a transaction-scoped `statement_timeout` and refreshes it with the remaining
deadline before repository operations and flush. A statement that exceeds the deadline is therefore
cancelled by PostgreSQL instead of holding the pooled connection until normal completion. The
setting uses `SET LOCAL`, so commit or rollback removes it automatically before the connection is
reused. PostgreSQL applies this setting with millisecond granularity, so cancellation follows normal
scheduler and network timing rather than a nanosecond-exact boundary.

Hibernate Reactive has no portable public API for cancelling an in-flight query. Other database
dialects retain the deadline checks before and after repository operations and mark expired
transactions rollback-only, but a statement already executing in the database might still finish.
`ReactiveTransactionExecutor` also uses coroutine cancellation rather than a database-specific
statement timeout.

### Transactional Events

The starter auto-configures Spring's reactive `TransactionalEventPublisher`. Use it instead of
`ApplicationEventPublisher` inside reactive transactions so the event carries the Reactor
transaction context required by `@TransactionalEventListener`.

```kotlin
@Service
class OrderService(
    private val events: TransactionalEventPublisher,
) {
    @Transactional
    suspend fun placeOrder(command: PlaceOrderCommand) {
        // Persist the order first...
        events.publishEvent(OrderPlaced(command.orderId)).awaitSingleOrNull()
    }
}

@Component
class OrderPlacedHandler {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun afterCommit(event: OrderPlaced) {
        // Runs only after a successful commit.
    }
}
```

The returned `Mono` must be awaited inside the transactional suspend function. Publishing through
the regular `ApplicationEventPublisher`, or subscribing to the reactive publisher separately,
loses the reactive transaction context and does not register the after-commit callback.

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

`transactional` and `readOnly` preserve caller coroutine context elements such as MDC/tracing
adapters and Reactor context while moving database work onto the required Vert.x dispatcher.
For a newly opened `readOnly` session, Hibernate dirty checking and automatic flush are disabled,
so changes made to loaded entities are not written implicitly.

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
