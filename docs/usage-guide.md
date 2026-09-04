# Usage Guide

Start with the setup chapter for your framework: [Spring Boot](#1-spring-boot-setup) or
[Ktor](#2-ktor-setup). Everything from [chapter 3](#3-repositories) onward applies to both, and
sections that only apply to one framework say so in their heading.

## 1. Spring Boot Setup

### 1.1 Basics

The starter reads the usual `spring.datasource` and `spring.jpa` properties and translates the
JDBC URL into the Vert.x form for you. Library-specific settings live under
`spring.jpa.properties.hibernate.reactive`.

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

### 1.2 Connection Pool

| Property | Description | Default |
| --- | --- | --- |
| `pool-size` | Maximum pool size | 10 |
| `connect-timeout` | How long to wait for a connection (ms) | Vert.x default |
| `idle-timeout` | How long an idle connection stays in the pool (ms) | Vert.x default |
| `max-wait-queue-size` | Maximum number of requests waiting for a connection | Vert.x default |

Set `connect-timeout` and `max-wait-queue-size` together in production. Leave both unset and the
wait queue is unbounded: when the database slows down, requests pile up instead of failing fast,
and the whole application stalls.

### 1.3 Vert.x Instance

The starter creates a Vert.x instance and exposes it as a `Vertx` bean. If your application
defines its own `Vertx` bean, the starter reuses it, so the whole app shares a single Vert.x.

The settings below go under `spring.jpa.properties.hibernate.reactive.vertx` and only take effect
when the starter creates the instance.

| Property | Description | Default |
| --- | --- | --- |
| `event-loop-pool-size` | Number of event-loop threads | 2 × CPU cores |
| `max-event-loop-execute-time` | How long a loop may be held before the blocked-thread checker warns | 2s |
| `blocked-thread-check-interval` | How often the blocked-thread checker runs | 1s |
| `warning-exception-time` | Hold time after which the warning includes a stack trace | 5s |

Durations use Spring's syntax (`500ms`, `2s`). Transaction blocks run on these event loops, so
lowering the two time thresholds in production makes any accidental blocking call, and its
location, show up in the logs right away.

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

### 1.4 Sharing Event Loops

In a WebFlux application, reactor-netty and Vert.x each spin up their own event-loop pool by
default. Set `share-event-loops: true` and the embedded Netty server runs on the Vert.x event loops
instead, so HTTP handling and database I/O share one thread pool.

```yaml
spring:
  jpa:
    properties:
      hibernate:
        reactive:
          vertx:
            share-event-loops: true
```

Requests then start on a Vert.x thread, which removes the thread hop on entering a transaction and
extends the blocked-thread checker and BlockHound ([chapter 6](#6-detecting-blocking-calls)) to the
web layer. If your application defines its own `ReactorResourceFactory` bean, that bean wins.

**There is a trade-off.** With separate pools, a blocking call inside a transaction only stalls the
database layer. With shared loops, the same mistake freezes every HTTP connection on that loop,
health checks included. Before enabling it, prove there are no blocking calls with BlockHound in
tests, and lower the blocked-thread checker thresholds in production.

### 1.5 SSL

| `ssl-mode` | Behavior |
| --- | --- |
| `disable` | No SSL (default). |
| `allow` | SSL only if the server demands it. |
| `prefer` | Try SSL first, fall back to plaintext. |
| `require` | SSL required; certificate verified against the JVM default trust store. |
| `verify-ca` | SSL required; certificate verified against the CA in `trust-certificate`. |
| `verify-full` | `verify-ca` plus hostname verification. |

`verify-ca` and `verify-full` require `trust-certificate`, the path to a PEM CA certificate. A
misconfiguration fails startup rather than silently downgrading to plaintext.

The `sslmode` and `currentSchema` parameters of a JDBC URL are still honored. They are moved into
Hibernate settings and stripped from the Vert.x URL; other parameters pass through untouched.

### 1.6 Repository Scanning

The starter scans the `@SpringBootApplication` package for interfaces that extend
`CoroutineCrudRepository` and registers them as beans. No `@Repository` needed.

```kotlin
// Default: scan from the @SpringBootApplication package.
@SpringBootApplication
class MyApplication

// Scan a different package.
@SpringBootApplication
@EnableHibernateReactiveRepositories(basePackages = ["com.example.repository"])
class MyApplication
```

## 2. Ktor Setup

Add `io.clroot:hibernate-reactive-coroutines-ktor` and the Vert.x client for your database. Ktor
3.5 is supported, and there is no runtime dependency on Spring.

### 2.1 Installing the Plugin

```kotlin
import io.clroot.hibernate.reactive.ktor.HibernateReactive
import io.clroot.hibernate.reactive.repository.CoroutineCrudRepository
import io.clroot.hibernate.reactive.repository.query.Query
import jakarta.data.Sort
import jakarta.data.page.Page
import jakarta.data.page.PageRequest

interface UserRepository : CoroutineCrudRepository<User, Long> {
    suspend fun findByActive(active: Boolean, pageRequest: PageRequest, sort: Sort<User>): Page<User>

    @Query("FROM User u WHERE u.email = :email")
    suspend fun findByEmail(email: String): User?
}

fun Application.module() {
    install(HibernateReactive) {
        database {
            url = "postgresql://localhost:5432/mydb"
            username = "user"
            password = "password"
            schemaGeneration = "validate"
            poolSize = 10
            showSql = false
            property("hibernate.format_sql", true)
        }
        repository<UserRepository, User, Long>()
    }
}
```

| `database {}` option | Description | Default |
| --- | --- | --- |
| `url` | Vert.x connection URL (`postgresql://host:port/db`) | none |
| `username`, `password` | Credentials | none |
| `schemaGeneration` | Value for `hibernate.hbm2ddl.auto` | `none` |
| `poolSize` | Maximum pool size | 10 |
| `showSql` | Log executed SQL | `false` |
| `property(name, value)` | Any other Hibernate property | |

Registering a repository with `repository()` also registers its entity with Hibernate. Only
entities without a repository need `entity<T>()`. The plugin never scans the classpath.

Resources are exposed as `Application` extension properties:

| Accessor | Returns |
| --- | --- |
| `hibernateRepository<R>()` | A registered repository |
| `hibernateTransactionExecutor` | `ReactiveTransactionExecutor` |
| `hibernateSessionProvider` | `ReactiveSessionProvider` |
| `hibernateSessionFactory` | `Mutiny.SessionFactory` |
| `hibernateReactive` | All of the above as `HibernateReactiveResources` |

The plugin does not wrap HTTP requests in a transaction. A repository call made outside a
transaction opens and closes its own session. To group several calls, use `transactional {}` from
[section 4.1](#41-reactivetransactionexecutor).

### 2.2 Ktor DI

Add `io.ktor:ktor-server-di` and set `dependencyInjection = true` to publish the repositories,
`ReactiveTransactionExecutor`, `ReactiveSessionProvider`, `Vertx`, and `HibernateReactiveResources`
to Ktor's DI container.

```kotlin
fun Application.module() {
    install(HibernateReactive) {
        database {
            url = environment.config.property("database.url").getString()
            username = environment.config.property("database.username").getString()
            password = environment.config.property("database.password").getString()
            schemaGeneration = "validate"
        }
        dependencyInjection = true
        repository<UserRepository, User, Long>()
    }

    val users: UserRepository by dependencies
    val tx: ReactiveTransactionExecutor by dependencies
    val userService = UserService(tx, users)

    routing {
        post("/users") {
            val request = call.receive<CreateUserRequest>()
            call.respond(HttpStatusCode.Created, userService.create(request))
        }
    }
}
```

The session factory is deliberately not published on its own; reach it through
`HibernateReactiveResources.sessionFactory`. This keeps Ktor DI's automatic `AutoCloseable`
cleanup from bypassing the ownership rules below.

### 2.3 External Resources and Ownership

If you already have a Vert.x instance or session factory, pass it in instead of `database {}`.

```kotlin
install(HibernateReactive) {
    vertx = applicationVertx
    sessionFactory = applicationSessionFactory
    closeExternalVertx = false          // default
    closeExternalSessionFactory = false // default

    repository<UserRepository, User, Long>()
}
```

- Resources the plugin created are closed on application shutdown, session factory first, then
  Vert.x.
- Resources you passed in are left alone unless the matching `closeExternal...` flag is set.
- Passing only a session factory is enough; the plugin discovers its Vert.x. If you also set
  `vertx`, it must be the same instance or startup fails.
- A custom session factory that does not expose Vert.x through Hibernate Reactive's `Implementor`
  SPI needs `vertx` set explicitly.

## 3. Repositories

### 3.1 Spring vs. Ktor

| | Spring Boot | Ktor |
| --- | --- | --- |
| Base interface | `org.springframework.data.repository.kotlin.CoroutineCrudRepository` | `io.clroot.hibernate.reactive.repository.CoroutineCrudRepository` |
| Query annotations | `io.clroot.hibernate.reactive.repository.query.{Query, Param, Modifying}` | same |
| Paging and sorting types | Spring Data `Pageable`, `Page`, `Slice`, `Sort` | Jakarta Data `PageRequest`, `Page`, `Sort`, `Order` |
| Registration | classpath scan | `repository<R, T, ID>()` |
| New-entity detection | `Persistable` first, then the shared rule | shared rule (`@Version`, then ID) |
| Created/modified timestamps | yes | Hibernate `@CreationTimestamp`, `@UpdateTimestamp` |
| Created/modified users | `ReactiveAuditorAware` | `ReactiveAuditorAware` |

The Ktor-side interface is a coroutine contract that extends Jakarta Data's `DataRepository`. It is
not a full Jakarta Data provider: lifecycle annotations, `@Find`, `Limit`, and the synchronous base
interfaces are out of scope.

### 3.2 Built-in CRUD

| Method | Returns |
| --- | --- |
| `save(entity)` | `T` |
| `saveAll(entities)` | `Flow<T>` |
| `findById(id)` | `T?` |
| `findAll()` | `Flow<T>` |
| `findAllById(ids)` | `Flow<T>` |
| `count()` | `Long` |
| `existsById(id)` | `Boolean` |
| `deleteById(id)`, `delete(entity)`, `deleteAllById(ids)`, `deleteAll()` | `Unit` |

- **`save` persists a new entity in place.** The generated ID lands on the instance you passed in.
  Existing entities are merged, so keep using the returned instance afterward.
- **An entity is new when its `@Version` is null, or, without `@Version`, when its ID is null or
  the primitive default.** Assigned IDs without `@Version` therefore always merge; on Spring,
  implement `Persistable` to say otherwise.
- **Deletes load first, then remove.** Cascades and `@PreRemove` fire, at the cost of one extra
  `SELECT`. For bulk deletes, write `@Modifying @Query("DELETE ...")`.
- **`Flow` is not streaming.** The whole result is loaded into memory and then emitted. Paginate
  large tables.
- **`@JvmInline` value classes work as ID types.**

### 3.3 Derived Queries

Method names alone define the query.

```kotlin
interface UserRepository : CoroutineCrudRepository<User, Long> {
    suspend fun findByEmail(email: String): User?
    suspend fun findByNameAndStatus(name: String, status: Status): User?
    suspend fun findAllByStatus(status: Status): List<User>
    suspend fun findAllByNameContaining(name: String): List<User>
    suspend fun existsByEmail(email: String): Boolean
    suspend fun countByStatus(status: Status): Long
    suspend fun deleteByEmail(email: String): Int   // Unit, Int, or Long
}
```

| Keyword | Example | Condition |
| --- | --- | --- |
| `And` | `findByNameAndEmail` | `name = ? AND email = ?` |
| `Or` | `findByNameOrEmail` | `name = ? OR email = ?` |
| `Between` | `findByAgeBetween` | `age BETWEEN ? AND ?` |
| `LessThan` / `GreaterThan` | `findByAgeLessThan` | `age < ?` |
| `Like` / `Containing` | `findByNameContaining` | `name LIKE %?%` |
| `StartingWith` / `EndingWith` | `findByNameStartingWith` | `name LIKE ?%` |
| `In` / `NotIn` | `findByStatusIn` | `status IN (?)` |
| `IsNull` / `IsNotNull` | `findByDeletedAtIsNull` | `deletedAt IS NULL` |
| `True` / `False` | `findByActiveTrue` | `active = TRUE` |
| `IgnoreCase` | `findByEmailIgnoreCase` | `LOWER(email) = LOWER(?)` |
| `OrderBy` | `findByStatusOrderByNameAsc` | `ORDER BY name ASC` |

- `Containing`, `StartingWith`, and `EndingWith` escape `%`, `_`, and `\` in the bound value.
  `Like` passes your pattern through as-is.
- `IgnoreCase` only works on `String` properties.
- Entity names come from the JPA metamodel, so `@Entity(name = "...")` is respected.
- Invalid method names, overloads with the same name and arity, and non-`suspend` query methods are
  rejected at startup.

### 3.4 @Query

Both Spring and Ktor use this library's annotations:

```kotlin
import io.clroot.hibernate.reactive.repository.query.Modifying
import io.clroot.hibernate.reactive.repository.query.Param
import io.clroot.hibernate.reactive.repository.query.Query
```

```kotlin
interface UserRepository : CoroutineCrudRepository<User, Long> {
    // Named parameters use the Kotlin parameter names.
    @Query("SELECT u FROM User u WHERE u.status = :status AND u.role = :role")
    suspend fun findByStatusAndRole(status: Status, role: Role): List<User>

    // Use @Param to bind under a different name.
    @Query("FROM User u WHERE u.status = :s")
    suspend fun findByStatus(@Param("s") status: Status): List<User>

    // Positional parameters start at ?1 and must be contiguous.
    @Query("SELECT u FROM User u WHERE u.age BETWEEN ?1 AND ?2")
    suspend fun findByAgeBetween(minAge: Int, maxAge: Int): List<User>

    // Native SQL is read-only.
    @Query("SELECT * FROM users WHERE status = :status", nativeQuery = true)
    suspend fun findNative(status: String): List<User>
}
```

- Named and positional parameters cannot be mixed, and bare `?` is not supported.
- PostgreSQL dollar-quoted literals and comments are rejected because Hibernate's HQL parser and
  native parser disagree on them.
- A `Sort`, or a `Pageable` carrying one, is appended after any `ORDER BY` already in the query.
  Native queries do not accept sort parameters.

#### Projections

Scalars, aggregates, and HQL constructor expressions are supported. Interface projections and
`Tuple` or array results are not.

```kotlin
data class UserSummary(val name: String, val age: Int)

@Query("SELECT COUNT(u) FROM User u WHERE u.status = :status")
suspend fun countByStatus(status: Status): Long

@Query("SELECT u.name FROM User u ORDER BY u.name")
suspend fun findNames(): List<String>

@Query("SELECT new com.example.UserSummary(u.name, u.age) FROM User u ORDER BY u.name")
suspend fun findSummaries(): List<UserSummary>
```

### 3.5 @Modifying

Mark `UPDATE` and `DELETE` queries with `@Modifying`. The return type is `Int`, `Long`, or `Unit`,
and native SQL is not allowed.

```kotlin
@Modifying
@Query("UPDATE User u SET u.status = :newStatus WHERE u.status = :oldStatus")
suspend fun updateStatus(oldStatus: Status, newStatus: Status): Int
```

Bulk updates do not refresh entities already in the session. If a later read in the same
transaction must see the new values, clear the session with
`@Modifying(clearAutomatically = true)`.

### 3.6 Pagination and Sorting

```kotlin
// Spring Boot
interface UserRepository : CoroutineCrudRepository<User, Long> {
    suspend fun findAllByStatus(status: Status, pageable: Pageable): Page<User>
    suspend fun findAllByStatus(status: Status, pageable: Pageable): Slice<User>
}

val page = users.findAllByStatus(Status.ACTIVE, PageRequest.of(0, 10, Sort.by("createdAt").descending()))
```

```kotlin
// Ktor
interface UserRepository : CoroutineCrudRepository<User, Long> {
    suspend fun findAllByStatus(status: Status, pageRequest: PageRequest, order: Order<User>): Page<User>
    suspend fun findByNameContaining(name: String, sort: Sort<User>): List<User>
}

val page = users.findAllByStatus(Status.ACTIVE, PageRequest.ofPage(1, 10, true), Order.by(Sort.desc("createdAt")))
```

| Type | COUNT query |
| --- | --- |
| Spring `Page` | executed |
| Spring `Slice` | skipped |
| Jakarta `Page` | skipped for `PageRequest.withoutTotal()`, in which case `totalElements()` throws |

Jakarta Data support is offset-based only; `CursoredPage` is not implemented.

#### Automatic COUNT Queries

A `@Query` returning `Page` gets an automatic COUNT query only when it is a simple entity query
such as `SELECT u FROM User u ...`. Provide `countQuery` yourself when the query has any of:

- projections, joins, `GROUP BY`, `HAVING`, set operations, or a trailing `SELECT`
- its own page limit, or a parameter inside `ORDER BY`
- a sort expression with a function or path navigation
- `nativeQuery = true`

```kotlin
@Query(
    value = "SELECT u FROM User u LEFT JOIN FETCH u.roles WHERE u.status = :status",
    countQuery = "SELECT COUNT(u) FROM User u WHERE u.status = :status",
)
suspend fun findWithRoles(status: Status, pageable: Pageable): Page<User>
```

### 3.7 Auditing

#### Spring Boot

`@CreatedDate` and `@LastModifiedDate` are filled in once the entity registers
`AuditingEntityListener`.

```kotlin
import io.clroot.hibernate.reactive.spring.boot.auditing.AuditingEntityListener
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate

@Entity
@EntityListeners(AuditingEntityListener::class)
class User(
    @Id @GeneratedValue val id: Long? = null,
    var name: String,
    @CreatedDate var createdAt: Instant? = null,
    @LastModifiedDate var updatedAt: Instant? = null,
    @CreatedBy var createdBy: String? = null,
    @LastModifiedBy var updatedBy: String? = null,
)
```

`@CreatedBy` and `@LastModifiedBy` need a `ReactiveAuditorAware` bean. In WebFlux the thread-local
`SecurityContextHolder` is empty, so read from `ReactiveSecurityContextHolder`.

```kotlin
import io.clroot.hibernate.reactive.repository.auditing.ReactiveAuditorAware

@Component
class SecurityAuditorAware : ReactiveAuditorAware<String> {
    override suspend fun getCurrentAuditor(): String? =
        ReactiveSecurityContextHolder.getContext()
            .awaitSingleOrNull()
            ?.authentication
            ?.name
}
```

#### Ktor

On Ktor, use Hibernate's `@CreationTimestamp` and `@UpdateTimestamp` to record creation and
modification times. No listener or Ktor plugin configuration is required.

```kotlin
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import io.clroot.hibernate.reactive.repository.auditing.CreatedBy
import io.clroot.hibernate.reactive.repository.auditing.LastModifiedBy

@Entity
class User(
    @Id @GeneratedValue val id: Long? = null,
    var name: String,
    @CreationTimestamp var createdAt: Instant? = null,
    @UpdateTimestamp var updatedAt: Instant? = null,
    @CreatedBy var createdBy: String? = null,
    @LastModifiedBy var updatedBy: String? = null,
)
```

Register a `ReactiveAuditorAware` with the plugin to populate the user fields. This SPI has no
dependency on a security library. For authenticated users, supply the user ID from a context such as
Ktor Authentication; for batch work, it can return a value such as `system`.

```kotlin
install(HibernateReactive) {
    auditorAware = ReactiveAuditorAware {
        currentUserId()
    }
    repository<UserRepository, User, Long>()
}
```

When the provider returns `null`, auditor fields remain unchanged. Bulk updates and native SQL bypass
entity lifecycle handling, so neither timestamp nor user auditing is applied.

## 4. Transactions

### 4.1 ReactiveTransactionExecutor

The programmatic transaction API, available on both frameworks. Inject it as a bean on Spring; on
Ktor, use `hibernateTransactionExecutor`.

```kotlin
class UserService(
    private val tx: ReactiveTransactionExecutor,
    private val users: UserRepository,
) {
    suspend fun rename(id: Long, name: String): User = tx.transactional {
        val user = users.findById(id) ?: error("User not found")
        user.name = name
        users.save(user)
    }

    suspend fun find(id: Long): User? = tx.readOnly {
        users.findById(id)
    }
}
```

- **`transactional {}`** opens a read-write transaction. It rolls back on exception and flushes
  then commits on success. Inside an existing transaction, it joins instead.
- **`readOnly {}`** opens a read-only session. Dirty checking and auto-flush are off, so entity
  changes are never written, and a write through a repository throws
  `ReadOnlyTransactionException`.
- `timeout` defaults to 30 seconds. Nested blocks get the shorter of their own timeout and the
  parent's remaining time.
- The block runs on a Vert.x event loop, but the caller's coroutine context (MDC, tracing, Reactor
  context) is carried along.

Do not launch detached coroutines, perform blocking I/O, or call external services inside the
block. BlockHound ([chapter 6](#6-detecting-blocking-calls)) catches blocking calls in tests.

### 4.2 @Transactional (Spring Boot only)

`@Transactional` works on `suspend` functions with no extra configuration.

```kotlin
@Service
class UserService(private val users: UserRepository) {

    @Transactional
    suspend fun createUser(name: String): User = users.save(User(name = name))

    @Transactional(readOnly = true)
    suspend fun findUser(id: Long): User? = users.findById(id)

    @Transactional
    suspend fun transfer(fromId: Long, toId: Long, amount: Int) {
        val from = users.findById(fromId) ?: error("sender not found")
        val to = users.findById(toId) ?: error("receiver not found")
        from.balance -= amount
        to.balance += amount
        // Both changes roll back if anything throws.
    }
}
```

Calling `tx.transactional {}` inside `@Transactional` joins the existing transaction. Opening a
write transaction inside `readOnly = true`, however, throws.

Nested `Propagation.REQUIRES_NEW` is unsupported: the parent holds its connection while the child
asks for another, which drains the pool under load. Nothing stops you from writing it, so watch out.
For work that must run after commit, use transaction events ([section 4.4](#44-transaction-events-spring-boot-only)).

### 4.3 Transaction Timeouts (Spring Boot only)

`@Transactional(timeout = ...)` becomes the transaction deadline.

On PostgreSQL, the starter also issues `SET LOCAL statement_timeout` and refreshes it with the
remaining time before each repository call and flush. A statement that overruns the deadline is
killed server-side, and the setting disappears when the transaction ends.

On other databases, only the deadline checks around repository calls apply; a statement already
running goes to completion, because Hibernate Reactive has no portable query-cancellation API.

### 4.4 Transaction Events (Spring Boot only)

To use `@TransactionalEventListener`, publish through the reactive `TransactionalEventPublisher`
the starter registers, not through `ApplicationEventPublisher`.

```kotlin
@Service
class OrderService(
    private val orders: OrderRepository,
    private val events: TransactionalEventPublisher,
) {
    @Transactional
    suspend fun placeOrder(command: PlaceOrderCommand) {
        val order = orders.save(Order.create(command))
        events.publishEvent(OrderPlaced(order.id)).awaitSingleOrNull()
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

Always await the `Mono` returned by `publishEvent`. Otherwise the transaction context is lost and
the after-commit callback never registers.

## 5. Loading Associations

Hibernate Reactive has no synchronous lazy loading. Touching an uninitialized association raises
`HR000069`, so load associations explicitly in one of two ways.

### 5.1 FETCH JOIN (recommended)

```kotlin
interface ParentRepository : CoroutineCrudRepository<Parent, Long> {
    @Query("SELECT p FROM Parent p LEFT JOIN FETCH p.children WHERE p.id = :id")
    suspend fun findByIdWithChildren(id: Long): Parent?
}
```

### 5.2 fetch() (Spring Boot only)

For an entity you already hold, load associations through `TransactionalAwareSessionProvider`.
This only works inside a transaction.

```kotlin
@Transactional(readOnly = true)
suspend fun getOrderDetails(orderId: Long): Order {
    val order = orders.findById(orderId) ?: error("Order not found")
    sessionProvider.fetchAll(order, Order::items, Order::payments)
    return order
}
```

| Method | Purpose |
| --- | --- |
| `fetch(entity, Entity::property)` | Load one association and return it |
| `fetchAll(entity, vararg properties)` | Load several associations at once |
| `fetchFromDetached(entity, Entity::class, Entity::property)` | Load an association of a detached entity |

## 6. Detecting Blocking Calls

Transaction blocks run on Vert.x event loops, so a blocking call inside one stalls everything else
on that loop. The `hibernate-reactive-coroutines-blockhound` module adds Vert.x event-loop threads
to [BlockHound](https://github.com/reactor/BlockHound)'s watch list; Reactor's own integration only
covers reactor-netty threads.

### 6.1 Setup

Add the module to the test classpath and it registers itself through `ServiceLoader`. BlockHound
comes along as a transitive dependency.

```kotlin
dependencies {
    testImplementation("io.clroot:hibernate-reactive-coroutines-blockhound:$hrcVersion")
}

tasks.withType<Test> {
    // Required for BlockHound's instrumentation on JDK 13+.
    jvmArgs(
        "-XX:+AllowRedefinitionToAddDeleteMethods",
        "-Djdk.attach.allowAttachSelf=true",
    )
}
```

### 6.2 Usage

Call `BlockHound.install()` once when tests start. From then on, a blocking call on an event loop
throws `BlockingOperationError`.

```kotlin
class BlockingDetectionTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun installBlockHound() = BlockHound.install()
    }

    @Test
    fun `detects blocking calls inside a transaction`() = runTest {
        assertThrows<BlockingOperationError> {
            tx.transactional {
                Thread.sleep(100)
            }
        }
    }
}
```

BlockHound instruments bytecode, so keep it to tests and development. In production, rely on the
blocked-thread checker from [section 1.3](#13-vertx-instance).
