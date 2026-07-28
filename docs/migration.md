# Migration from Spring Data JPA

**[🇰🇷 한국어](migration.ko.md)**

A guide for migrating from Spring Data JPA to Hibernate Reactive Coroutines.

---

## JPA Feature Coverage

**Overall Coverage: ~85-90%** - All core features are supported.

### Repository Features

| Feature                              | Supported | Notes                                                    |
| ------------------------------------ | :-------: | -------------------------------------------------------- |
| `CrudRepository` methods             |     ✅     | save, findById, findAll, delete, count, existsById, etc. |
| `findBy*` query methods              |     ✅     | PartTree-based auto-generation                           |
| `countBy*`, `existsBy*`, `deleteBy*` |     ✅     |                                                          |
| LIKE search                          |     ✅     | Containing, StartingWith, EndingWith                     |
| Comparison operators                 |     ✅     | GreaterThan, LessThan, Between, etc.                     |
| `@Query` (JPQL)                      |     ✅     | Named/Positional Parameters                              |
| `@Query` (Native)                    |     ✅     | Read-only; Page requires `countQuery`                    |
| `@Modifying`                         |     ✅     | JPQL UPDATE/DELETE; `Int`/`Unit`, optional auto-clear    |
| Pagination (`Page`, `Slice`)         |     ✅     | Smart COUNT skip optimization                            |

HQL/JPQL `@Query` methods returning `Page` derive COUNT automatically only for simple entity
`SELECT`/`FROM` queries. Projections, joins, grouping, set operations, trailing selects,
query-level pagination, and parameterized ordering require an explicit `countQuery`.

### Transactions

| Feature                   | Supported | Notes                                            |
| ------------------------- | :-------: | ------------------------------------------------ |
| `@Transactional`          |     ✅     | Supports suspend functions                       |
| readOnly / timeout        |     ✅     |                                                  |
| Propagation.REQUIRED      |     ✅     | Default                                          |
| Propagation.REQUIRES_NEW  |     ⚠️     | Connection pool exhaustion risk, limited nesting |
| Programmatic Transaction  |     ✅     | ReactiveTransactionExecutor                      |

### JPA Behaviors

| Feature                    | Supported | Notes                         |
| -------------------------- | :-------: | ----------------------------- |
| Dirty Checking             |     ✅     | Auto-persist on commit        |
| First-level Cache          |     ✅     | Same instance within tx       |
| Optimistic Locking         |     ✅     | `@Version`                    |
| Entity Lifecycle Callbacks |     ✅     | @PrePersist, @PreUpdate, etc. |
| Lazy Loading               |     ✅     | Use `fetch()` method          |
| Pessimistic Locking        |     ❌     |                               |

### Unsupported Features

These are rejected at application startup with an explanatory message, not at first call.

| Feature                                   | Alternative                                     |
| ----------------------------------------- | ----------------------------------------------- |
| Specification (dynamic queries)           | Write directly with `@Query`                    |
| QueryByExample                            | Combine conditional methods                     |
| Projection (interface-based)              | Use FETCH JOIN and map in Kotlin                |
| `@EntityGraph`                            | FETCH JOIN or `fetch()` method                  |
| Native `@Modifying`                       | Use JPQL instead                                |
| Scalar/aggregate/DTO results in `@Query`  | Return the entity type, or use `count…`/`exists…` derived methods |
| Non-suspend (including `Flow`) query methods | Declare `suspend fun … : List<T>`            |
| Overloads with the same name and arity    | Give the methods distinct names                 |
| `Top`/`First` combined with `Pageable`    | Use one or the other                            |
| Sorting a native `@Query`                 | Put `ORDER BY` inside the query                 |

---

## Behavior Notes

Differences worth knowing before you migrate, beyond the feature table above.

**Deletes load before removing.** `deleteById`, `delete`, `deleteAll`, `deleteAllById` and derived
`deleteBy…` methods fetch the target entities and remove them one by one, matching
`SimpleJpaRepository`. Cascades, `@Version` checks and `@PreRemove` callbacks all fire, and the
persistence context stays consistent. The cost is a `SELECT` before the `DELETE`; for bulk deletion
without cascade semantics, write an explicit `@Modifying @Query("DELETE …")`.

**Derived `deleteBy…` can return the deleted count.** Declare the method as `Unit`, `Int` or `Long`.

**`LIKE` values are escaped.** `Containing`, `StartingWith` and `EndingWith` escape `%`, `_` and `\`
in the bound value, so `findByNameContaining("%")` matches a literal percent sign rather than every
row. The explicit `Like` keyword does not escape — there you supply the pattern yourself.

**`IgnoreCase` compares both sides in lower case.** `IgnoreCase` on a non-String property is rejected
at startup; `AllIgnoreCase` applies only to String properties.

**`Sort` applies to `@Query` methods.** A `Sort` parameter or a sorted `Pageable` is appended to the
query's own `ORDER BY` clause, so an ordering written into the query keeps priority.

**Entity names come from the JPA metamodel.** `@Entity(name = "…")` and same-simple-name entities in
different packages work correctly.

**`Flow` is not streaming.** `findAll()` and other `Flow`-returning methods load the full result set
before emitting. Use `Pageable` for large tables.

**Mixing `@Transactional` and `tx.transactional {}` is safe.** `tx.transactional {}` joins an active
Spring transaction instead of opening a second session, and refuses to upgrade a
`@Transactional(readOnly = true)` transaction to a writable one.

---

## Migration Steps

### 1. Change Dependencies

```kotlin
// Remove
implementation("org.springframework.boot:spring-boot-starter-data-jpa")

// Add
implementation("io.clroot:hibernate-reactive-coroutines-spring-boot-starter:1.1.0")
implementation("io.vertx:vertx-pg-client:4.5.16")  // or MySQL
```

### 2. Modify Repository Interfaces

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

**Changes:** `JpaRepository` → `CoroutineCrudRepository`, add `suspend` to all methods

### 3. Modify Service Layer

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

**Changes:** Add `suspend`, `findById().orElse(null)` → `findById()` (nullable return)

### 4. Convert Lazy Loading

```kotlin
// Before - Does NOT work in Hibernate Reactive
parent.children.size  // HR000069 error

// After - Option 1: FETCH JOIN (Recommended)
@Query("SELECT p FROM Parent p LEFT JOIN FETCH p.children WHERE p.id = :id")
suspend fun findByIdWithChildren(id: Long): Parent?

// After - Option 2: fetch() method
sessionProvider.fetch(parent, Parent::children)
```

### 5. Replace Unsupported Features

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

**REQUIRES_NEW → Event-based:**
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

## Checklist

- [ ] Change dependencies
- [ ] Add `suspend` to Repository interfaces
- [ ] Add `suspend` to Service methods
- [ ] Convert Lazy Loading → FETCH JOIN or `fetch()`
- [ ] Convert `@EntityGraph` → FETCH JOIN
- [ ] Convert `REQUIRES_NEW` → Event-based
- [ ] Convert Native @Modifying → JPQL
- [ ] Update tests (use `runBlocking` or `runTest`)
