# Hibernate Reactive Coroutines

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-blue.svg)](https://kotlinlang.org)
[![Hibernate Reactive](https://img.shields.io/badge/Hibernate%20Reactive-4.5.2-green.svg)](https://hibernate.org/reactive/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4%20%7C%204.0-brightgreen.svg)](https://spring.io/projects/spring-boot)

> Coroutine repositories and transaction primitives for **Hibernate Reactive**, with Spring Boot and Ktor integrations.

**[🇰🇷 한국어 문서](README.ko.md)**

---

## What is this?

This library provides first-class Kotlin Coroutines support for Hibernate Reactive. Use the Spring Boot starters for Spring Data-style auto-configuration, or the Ktor plugin with the framework-neutral Jakarta Data repository contract.

### Why use this?

- **Spring Data JPA-like API**: Use familiar patterns like `findByEmail`, `existsByStatus`, and `@Query` annotations
- **Native Kotlin Coroutines**: All repository methods are `suspend` functions - no `Uni`/`Mono` conversion needed
- **Spring Boot Auto-configuration**: Just add the starter dependency and start coding
- **Ktor application plugin**: Explicit registration, application accessors, and resource ownership
- **Non-blocking Database Access**: Built on Hibernate Reactive and Vert.x for true reactive performance

## Features

- `CoroutineCrudRepository` interface with suspend functions
- **Query method derivation** (`findByEmail`, `findAllByStatus`, `countByActive`, etc.)
- **`@Query` annotation** for custom JPQL/HQL queries, including scalar, aggregate, and constructor DTO projections
- **Pagination support** (`Page`, `Slice`, `Pageable`)
- **Spring `@Transactional`** integration with coroutine context propagation
- **Auditing** (`@CreatedDate`, `@LastModifiedDate`, `@CreatedBy`, `@LastModifiedBy`)
- **Application-owned Vert.x**: exposed as a Spring bean, with opt-in event-loop sharing for the WebFlux Netty server
- **Blocking call detection**: BlockHound integration module + Vert.x blocked-thread checker settings

**Spring Data JPA feature coverage: ~85-90%** — See [Migration Guide](docs/migration.md) for details.

## Modules

- `hibernate-reactive-coroutines-core`: coroutine session and transaction primitives
- `hibernate-reactive-coroutines-repository`: framework-neutral query/runtime plus a Jakarta Data-facing coroutine repository contract
- `hibernate-reactive-coroutines-ktor`: Ktor 3 application plugin with explicit entity/repository registration
- `hibernate-reactive-coroutines-spring-boot-starter*`: Spring Boot 3 and 4 integrations
- `hibernate-reactive-coroutines-blockhound`: optional blocking-call detection integration

## Requirements

- Java 17 or later
- Ktor 3.5.x for the Ktor integration
- Spring Boot 3.4.x or 4.x for the Spring integrations

> **Hibernate ORM 7 is required.** Hibernate Reactive 4.5 runs on Hibernate ORM 7.4, and this starter
> publishes that as a dependency constraint. Because Spring Framework 6.x (Spring Boot 3.x) does not
> support Hibernate ORM 7, **do not combine this starter with `spring-boot-starter-data-jpa` in a
> Spring Boot 3 application** — the blocking JPA half will fail to start. On Spring Boot 4 the two can
> coexist. Run the reactive and blocking persistence layers in separate modules if you need both on
> Boot 3.

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    // For Spring Boot 3.x
    implementation("io.clroot:hibernate-reactive-coroutines-spring-boot-starter:1.3.0")

    // For Spring Boot 4.x
    implementation("io.clroot:hibernate-reactive-coroutines-spring-boot-starter-boot4:1.3.0")

    // Database driver (choose one)
    implementation("io.vertx:vertx-pg-client:5.1.5")      // PostgreSQL
    // implementation("io.vertx:vertx-mysql-client:5.1.5") // MySQL
}
```

### Gradle (Groovy)

```groovy
dependencies {
    // For Spring Boot 3.x
    implementation 'io.clroot:hibernate-reactive-coroutines-spring-boot-starter:1.3.0'

    // For Spring Boot 4.x
    implementation 'io.clroot:hibernate-reactive-coroutines-spring-boot-starter-boot4:1.3.0'

    // Database driver
    implementation 'io.vertx:vertx-pg-client:5.1.5'
}
```

### Maven

```xml
<!-- For Spring Boot 3.x -->
<dependency>
    <groupId>io.clroot</groupId>
    <artifactId>hibernate-reactive-coroutines-spring-boot-starter</artifactId>
    <version>1.3.0</version>
</dependency>

<!-- For Spring Boot 4.x -->
<dependency>
    <groupId>io.clroot</groupId>
    <artifactId>hibernate-reactive-coroutines-spring-boot-starter-boot4</artifactId>
    <version>1.3.0</version>
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
  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect
    properties:
      hibernate:
        reactive:
          pool-size: 10  # Connection pool size (default: 10)
```

## Ktor Quick Start

Add the published Ktor integration and a Vert.x database client:

```kotlin
dependencies {
    implementation("io.clroot:hibernate-reactive-coroutines-ktor:<version>")
    implementation("io.vertx:vertx-pg-client:5.1.5")
}
```

Register entities and Jakarta Data coroutine repositories explicitly. The plugin does not scan the
classpath and does not create a request-wide session or transaction:

```kotlin
fun Application.module() {
    install(HibernateReactive) {
        database {
            url = "postgresql://localhost:5432/mydb"
            username = "user"
            password = "password"
            schemaGeneration = "validate"
        }
        entity<User>()
        repository<UserRepository, User, Long>()
    }

    routing {
        post("/users") {
            val users = hibernateRepository<UserRepository>()
            val saved = hibernateTransactionExecutor.transactional {
                users.save(User(name = "Alice", email = "alice@example.com"))
            }
            call.respond(saved.id!!)
        }
    }
}
```

The plugin closes the session factory and Vert.x instances it creates. Externally supplied instances
are preserved by default; set `closeExternalSessionFactory` or `closeExternalVertx` only when the
plugin should take ownership. Set `dependencyInjection = true` to publish the resource registry,
session provider, transaction executor, and Vert.x through the optional `ktor-server-di` module.
See the [Usage Guide](docs/usage-guide.md#ktor-integration) for external-factory and transaction
boundary details.

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

### Blocking Call Detection (BlockHound)

`transactional {}` blocks run on Vert.x event loop threads, where a single blocking call
(`Thread.sleep`, a synchronous HTTP client, file I/O, ...) stalls every transaction pinned to that
loop. [BlockHound](https://github.com/reactor/BlockHound) can catch such calls in tests — but it only
inspects threads that are *marked* non-blocking, and Vert.x event loop threads are not marked by
default. The `hibernate-reactive-coroutines-blockhound` module registers that marking automatically:

```kotlin
dependencies {
    testImplementation("io.clroot:hibernate-reactive-coroutines-blockhound:1.3.0")
}

tasks.withType<Test>().configureEach {
    // Required for BlockHound's runtime instrumentation on JDK 13+
    jvmArgs("-XX:+AllowRedefinitionToAddDeleteMethods", "-Djdk.attach.allowAttachSelf=true")
}
```

```kotlin
BlockHound.install() // picks up the integration via ServiceLoader

tx.transactional {
    Thread.sleep(100) // throws BlockingOperationError
}
```

Pairing it with `org.jetbrains.kotlinx:kotlinx-coroutines-debug` is recommended so coroutine
internals are allowlisted. BlockHound instruments bytecode, so keep it in tests and local
development; for production detection rely on the Vert.x built-in blocked-thread checker.

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
