# Hibernate Reactive Coroutines

[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.0-blue.svg)](https://kotlinlang.org)
[![Hibernate Reactive](https://img.shields.io/badge/Hibernate%20Reactive-3.1.0-green.svg)](https://hibernate.org/reactive/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4%20%7C%204.0-brightgreen.svg)](https://spring.io/projects/spring-boot)

> A **Hibernate Reactive Spring Boot Starter** that brings Spring Data JPA-like convenience to Kotlin Coroutines.

**[🇰🇷 한국어 문서](README.ko.md)**

---

## What is this?

This library provides a **Spring Boot starter for Hibernate Reactive** with first-class Kotlin Coroutines support. If you're looking for a way to use Hibernate Reactive with Spring Boot while maintaining the familiar Spring Data JPA developer experience, this is it.

### Why use this?

- **Spring Data JPA-like API**: Use familiar patterns like `findByEmail`, `existsByStatus`, and `@Query` annotations
- **Native Kotlin Coroutines**: All repository methods are `suspend` functions - no `Uni`/`Mono` conversion needed
- **Spring Boot Auto-configuration**: Just add the starter dependency and start coding
- **Non-blocking Database Access**: Built on Hibernate Reactive and Vert.x for true reactive performance

## Features

- `CoroutineCrudRepository` interface with suspend functions
- **Query method derivation** (`findByEmail`, `findAllByStatus`, `countByActive`, etc.)
- **`@Query` annotation** for custom JPQL/HQL queries
- **Pagination support** (`Page`, `Slice`, `Pageable`)
- **Spring `@Transactional`** integration with coroutine context propagation
- **Auditing** (`@CreatedDate`, `@LastModifiedDate`, `@CreatedBy`, `@LastModifiedBy`)

**Spring Data JPA feature coverage: ~85-90%** — See [Migration Guide](docs/migration.md) for details.

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    // For Spring Boot 3.x
    implementation("io.clroot:hibernate-reactive-coroutines-spring-boot-starter:1.0.0")

    // For Spring Boot 4.x
    implementation("io.clroot:hibernate-reactive-coroutines-spring-boot-starter-boot4:1.0.0")

    // Database driver (choose one)
    implementation("io.vertx:vertx-pg-client:4.5.16")      // PostgreSQL
    // implementation("io.vertx:vertx-mysql-client:4.5.16") // MySQL
}
```

### Gradle (Groovy)

```groovy
dependencies {
    // For Spring Boot 3.x
    implementation 'io.clroot:hibernate-reactive-coroutines-spring-boot-starter:1.0.0'

    // For Spring Boot 4.x
    implementation 'io.clroot:hibernate-reactive-coroutines-spring-boot-starter-boot4:1.0.0'

    // Database driver
    implementation 'io.vertx:vertx-pg-client:4.5.16'
}
```

### Maven

```xml
<!-- For Spring Boot 3.x -->
<dependency>
    <groupId>io.clroot</groupId>
    <artifactId>hibernate-reactive-coroutines-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- For Spring Boot 4.x -->
<dependency>
    <groupId>io.clroot</groupId>
    <artifactId>hibernate-reactive-coroutines-spring-boot-starter-boot4</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Quick Start

### 1. Define your Entity

```kotlin
@Entity
@Table(name = "users")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    var name: String,

    @Column(unique = true)
    var email: String,

    @Enumerated(EnumType.STRING)
    var status: Status = Status.ACTIVE
)

enum class Status { ACTIVE, INACTIVE }
```

### 2. Define your Repository

```kotlin
interface UserRepository : CoroutineCrudRepository<User, Long> {
    // Query methods - automatically implemented!
    suspend fun findByEmail(email: String): User?
    suspend fun findAllByStatus(status: Status): List<User>
    suspend fun existsByEmail(email: String): Boolean
    suspend fun countByStatus(status: Status): Long

    // Custom JPQL query
    @Query("SELECT u FROM User u WHERE u.name LIKE :pattern")
    suspend fun searchByName(pattern: String): List<User>

    // Pagination
    suspend fun findAllByStatus(status: Status, pageable: Pageable): Page<User>
}
```

### 3. Use in your Service

```kotlin
@Service
class UserService(private val userRepository: UserRepository) {

    @Transactional
    suspend fun createUser(name: String, email: String): User {
        return userRepository.save(User(name = name, email = email))
    }

    @Transactional(readOnly = true)
    suspend fun findByEmail(email: String): User? {
        return userRepository.findByEmail(email)
    }

    @Transactional(readOnly = true)
    suspend fun listActiveUsers(page: Int, size: Int): Page<User> {
        return userRepository.findAllByStatus(
            Status.ACTIVE,
            PageRequest.of(page, size)
        )
    }
}
```

### 4. Configure

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/mydb
    username: user
    password: password

kotlin:
  hibernate:
    reactive:
      pool-size: 10  # Connection pool size (default: 10)
```

## Documentation

| Document | Description |
|----------|-------------|
| [Usage Guide](docs/usage-guide.md) | Configuration, usage, and examples |
| [Migration Guide](docs/migration.md) | JPA compatibility and migration from Spring Data JPA |
| [Internals](docs/internals.md) | Architecture and how it works |

## Important Notes

### Lazy Loading

Synchronous lazy loading (`parent.children.size`) is not supported in Hibernate Reactive. Use one of these alternatives:

```kotlin
// Option 1: FETCH JOIN (recommended)
@Query("SELECT p FROM Parent p LEFT JOIN FETCH p.children WHERE p.id = :id")
suspend fun findByIdWithChildren(id: Long): Parent?

// Option 2: Explicit fetch
val children = sessionProvider.fetch(parent, Parent::children)
```

### REQUIRES_NEW Not Supported

`Propagation.REQUIRES_NEW` is not supported due to potential connection pool exhaustion in reactive environments.

## Comparison with Alternatives

| Feature | This Library | Spring Data R2DBC | Quarkus Panache |
|---------|--------------|-------------------|-----------------|
| JPA/Hibernate | ✅ Full JPA | ❌ No JPA | ✅ Hibernate ORM |
| Kotlin Coroutines | ✅ Native | ⚠️ Requires conversion | ⚠️ Mutiny-based |
| Spring Boot | ✅ Auto-config | ✅ Auto-config | ❌ Quarkus only |
| Query Methods | ✅ Derived queries | ✅ Derived queries | ⚠️ Limited |
| Entity Relationships | ✅ Full support | ⚠️ Limited | ✅ Full support |

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

MIT License

---

**Keywords**: hibernate reactive, spring boot starter, kotlin coroutines, reactive repository, spring data jpa alternative, non-blocking database, suspend functions, reactive spring, vertx, mutiny