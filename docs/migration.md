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
| `@Query` (Native)                    |     ✅     | Read-only, countQuery required                           |
| `@Modifying`                         |     ✅     | JPQL UPDATE/DELETE                                       |
| Pagination (`Page`, `Slice`)         |     ✅     | Smart COUNT skip optimization                            |

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

| Feature                         | Alternative                    |
| ------------------------------- | ------------------------------ |
| Specification (dynamic queries) | Write directly with `@Query`   |
| QueryByExample                  | Combine conditional methods    |
| Projection (interface-based)    | Use `SELECT new DTO(...)`      |
| `@EntityGraph`                  | FETCH JOIN or `fetch()` method |
| Native @Modifying               | Use JPQL instead               |

---

## Migration Steps

### 1. Change Dependencies

```kotlin
// Remove
implementation("org.springframework.boot:spring-boot-starter-data-jpa")

// Add
implementation("io.clroot:hibernate-reactive-coroutines-spring-boot-starter:1.0.0")
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
