# Migrating from Spring Data JPA

Check [chapter 1](#1-whats-supported) for the features you rely on, work through
[chapter 2](#2-migration-steps) in order, then review [chapter 3](#3-behavioral-differences) for
the places where results can differ.

## 1. What's Supported

✅ works as-is, ⚠️ works with limits, ❌ not supported.

### 1.1 Repositories

| Feature | | Notes |
| --- | :---: | --- |
| `CrudRepository` methods | ✅ | |
| `findBy*` derived queries | ✅ | |
| `countBy*`, `existsBy*`, `deleteBy*` | ✅ | `deleteBy*` can return the count as `Int` or `Long` |
| LIKE and comparison keywords | ✅ | `Containing`, `StartingWith`, `Between`, ... |
| `@Query` (JPQL) | ✅ | named and positional parameters |
| `@Query` scalar, aggregate, and DTO results | ✅ | HQL constructor expressions |
| `@Query` (native) | ⚠️ | read-only; `Page` results need `countQuery` |
| `@Modifying` | ⚠️ | JPQL only |
| `Page`, `Slice`, `Sort` | ✅ | |
| Specification, Query by Example | ❌ | write a `@Query` |
| Interface projections, `Tuple` / array results | ❌ | use constructor DTOs |
| `@EntityGraph` | ❌ | use FETCH JOIN or `fetch()` |

### 1.2 Transactions

| Feature | | Notes |
| --- | :---: | --- |
| `@Transactional`, `readOnly`, `timeout` | ✅ | on PostgreSQL, `timeout` also sets `statement_timeout` |
| `Propagation.REQUIRED` | ✅ | default |
| `Propagation.REQUIRES_NEW` | ⚠️ | works standalone; nested use is unsupported because it can drain the pool |
| Programmatic transactions | ✅ | `ReactiveTransactionExecutor` |
| `@TransactionalEventListener` | ✅ | publish through the reactive `TransactionalEventPublisher` |

### 1.3 JPA Behavior

| Feature | | Notes |
| --- | :---: | --- |
| Dirty checking, first-level cache | ✅ | |
| Optimistic locking (`@Version`) | ✅ | |
| Entity lifecycle callbacks | ✅ | `@PrePersist`, `@PreUpdate`, ... |
| Auditing | ✅ | `@CreatedDate`, `@LastModifiedDate`, `@CreatedBy`, `@LastModifiedBy` |
| Lazy loading | ⚠️ | not synchronous; use FETCH JOIN or `fetch()` |
| Pessimistic locking | ❌ | |

### 1.4 Startup Validation

Unsupported features and invalid repository declarations are rejected **at startup**, not on first
call. Booting the application once after migrating surfaces most of what is left.

| Rejected declaration | Fix |
| --- | --- |
| Non-`suspend` query method (including `Flow` returns) | declare `suspend fun ...: List<T>` |
| Overloads with the same name and arity | use distinct names |
| `Top`/`First` combined with `Pageable` | pick one |
| Native `@Query` with `Sort` or a sorted `Pageable` | put `ORDER BY` in the query |
| Native `@Modifying` | rewrite in JPQL |
| `IgnoreCase` on a non-`String` property | drop the keyword |
| `Page`-returning `@Query` whose COUNT cannot be derived | add `countQuery` |

## 2. Migration Steps

### 2.1 Dependencies

On Spring Boot 3, remove `spring-boot-starter-data-jpa`. Spring Framework 6 cannot run Hibernate
ORM 7, so the two starters cannot coexist and the application will not boot. On Spring Boot 4 they
can.

```kotlin
val hrcVersion = "2.0.1"

dependencies {
    // Remove
    // implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Add
    implementation("io.clroot:hibernate-reactive-coroutines-spring-boot-starter:$hrcVersion")
    // implementation("io.clroot:hibernate-reactive-coroutines-spring-boot-starter-boot4:$hrcVersion")
    runtimeOnly("io.vertx:vertx-pg-client:5.1.5") // or vertx-mysql-client
}
```

Leave `spring.datasource` and `spring.jpa` as they are.

### 2.2 Repository Interfaces

Change the base interface, add `suspend` to every method, and switch the query-annotation imports.

```kotlin
// Before
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface UserRepository : JpaRepository<User, Long> {
    fun findByEmail(email: String): User?

    @Query("SELECT u FROM User u WHERE u.status = :status")
    fun findByStatus(status: Status): List<User>
}

// After
import io.clroot.hibernate.reactive.repository.query.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface UserRepository : CoroutineCrudRepository<User, Long> {
    suspend fun findByEmail(email: String): User?

    @Query("SELECT u FROM User u WHERE u.status = :status")
    suspend fun findByStatus(status: Status): List<User>
}
```

`flush()`, `saveAndFlush()`, and `getReferenceById()` are gone. Flushing happens when the
transaction ends, and reference proxies become plain `findById()` calls.

### 2.3 Service Layer

Add `suspend` to every method that calls a repository. `findById()` now returns a nullable instead
of `Optional`, so `orElseThrow` becomes `?:`. The `suspend` chain has to reach the controller.

```kotlin
// Before
@Transactional
fun rename(id: Long, name: String): User {
    val user = userRepository.findById(id).orElseThrow { NotFoundException() }
    user.name = name
    return user
}

// After
@Transactional
suspend fun rename(id: Long, name: String): User {
    val user = userRepository.findById(id) ?: throw NotFoundException()
    user.name = name
    return user
}
```

### 2.4 Lazy Loading

Every place that relied on touching an association to load it has to change. Touching an
uninitialized association now raises `HR000069`.

```kotlin
// Before
val parent = parentRepository.findById(id).orElseThrow()
parent.children.size

// After (recommended): FETCH JOIN
@Query("SELECT p FROM Parent p LEFT JOIN FETCH p.children WHERE p.id = :id")
suspend fun findByIdWithChildren(id: Long): Parent?

// After (alternative): fetch()
val parent = parentRepository.findById(id) ?: throw NotFoundException()
sessionProvider.fetch(parent, Parent::children)
```

See [Usage Guide, chapter 5](usage-guide.md#5-loading-associations) for details.

### 2.5 Replacing Unsupported Features

**`@EntityGraph` → FETCH JOIN**

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

**`REQUIRES_NEW` → transaction events.** Work that must commit independently of the parent moves
into a listener that runs after the parent commits.

```kotlin
// Before
@Transactional(propagation = Propagation.REQUIRES_NEW)
fun audit(event: AuditEvent) { ... }

// After
@Transactional
suspend fun placeOrder(command: PlaceOrderCommand) {
    orders.save(Order.create(command))
    events.publishEvent(OrderPlaced(command.orderId)).awaitSingleOrNull()
}

@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
fun audit(event: OrderPlaced) { ... }
```

The publishing rules are in [Usage Guide, section 4.4](usage-guide.md#44-transaction-events-spring-boot-only).

**Native `@Modifying` → JPQL**

```kotlin
// Before
@Modifying
@Query(value = "UPDATE users SET status = ?1", nativeQuery = true)
fun updateStatus(status: String): Int

// After
@Modifying
@Query("UPDATE User u SET u.status = :status")
suspend fun updateStatus(status: Status): Int
```

**Specification, Query by Example → `@Query`.** For many optional criteria, write JPQL with
nullable parameters.

```kotlin
@Query("""
    SELECT u FROM User u
    WHERE (:status IS NULL OR u.status = :status)
      AND (:name IS NULL OR u.name LIKE CONCAT('%', :name, '%'))
""")
suspend fun search(status: Status?, name: String?, pageable: Pageable): Page<User>
```

### 2.6 Tests

Run tests inside `runTest` or `runBlocking`. To make sure no blocking call survived inside a
transaction, add the BlockHound module from
[Usage Guide, chapter 6](usage-guide.md#6-detecting-blocking-calls).

## 3. Behavioral Differences

Not features, but places where the same code produces a different result.

| | Spring Data JPA | This library |
| --- | --- | --- |
| `findAll()` return type | `List<T>` | `Flow<T>`, but not streaming: the full result is loaded, then emitted |
| Wildcards in `Containing` etc. | escapes `%` and `_` | also escapes `\`; `Like` escapes nothing |
| `IgnoreCase` | dialect-dependent | `LOWER()` on both sides; `String` properties only |
| `@Query` plus `Sort` | appended after `ORDER BY` | same; native queries accept no `Sort` |
| Programmatic transaction inside `@Transactional` | joins | joins; but a write transaction inside `readOnly = true` throws |
| Blocking calls inside a transaction | allowed | forbidden; they stall the event loop |

Delete semantics (load, then remove), the return value of `save()`, and session handling after a
bulk `@Modifying` match Spring Data JPA.

## 4. Checklist

- [ ] Removed `spring-boot-starter-data-jpa`; added the starter and a Vert.x client
- [ ] Repositories extend `CoroutineCrudRepository` and every method is `suspend`
- [ ] `@Query`, `@Param`, `@Modifying` imports switched
- [ ] Services and controllers are `suspend`; `Optional` handling replaced with nullable handling
- [ ] Association access rewritten as FETCH JOIN or `fetch()`
- [ ] `@EntityGraph`, `REQUIRES_NEW`, native `@Modifying`, and Specifications replaced
- [ ] Tests wrapped in `runTest`; BlockHound added
- [ ] Application boots with no repository validation errors
