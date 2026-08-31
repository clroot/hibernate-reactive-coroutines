# Hibernate Reactive Coroutines

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-blue.svg)](https://kotlinlang.org)
[![Hibernate Reactive](https://img.shields.io/badge/Hibernate%20Reactive-4.5.2-green.svg)](https://hibernate.org/reactive/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4%20%7C%204.0-brightgreen.svg)](https://spring.io/projects/spring-boot)

> Hibernate Reactive를 Spring Data JPA처럼 사용하세요.

Hibernate Reactive + Kotlin Coroutines 환경에서 Spring Data JPA의 편의성을 제공하는 라이브러리입니다.

## 주요 기능

- `CoroutineCrudRepository` 인터페이스 지원
- 쿼리 메서드 자동 생성 (`findByEmail`, `existsByStatus` 등)
- `@Query` 어노테이션으로 커스텀 JPQL
- 페이지네이션 (`Page`, `Slice`, `Pageable`)
- Spring `@Transactional` 통합
- Auditing (`@CreatedDate`, `@LastModifiedDate`, `@CreatedBy`, `@LastModifiedBy`)
- Vert.x 인스턴스를 Spring 빈으로 소유·공유, WebFlux 서버와의 이벤트 루프 통합(opt-in)
- 블로킹 호출 탐지: BlockHound 통합 모듈 + Vert.x blocked-thread checker 설정 노출

**Spring Data JPA 기능 커버리지: ~85-90%** - 자세한 내용은 [마이그레이션 가이드](docs/migration.ko.md)를 참고하세요.

## 요구사항

- Java 17 이상
- Spring Boot 3.4.x 또는 4.x

> **Hibernate ORM 7이 필요합니다.** Hibernate Reactive 4.5는 Hibernate ORM 7.4 위에서 동작하며,
> 이 스타터는 해당 버전을 의존성 제약으로 발행합니다. Spring Framework 6.x(Spring Boot 3.x)는
> Hibernate ORM 7을 지원하지 않으므로, **Spring Boot 3 애플리케이션에서 `spring-boot-starter-data-jpa`와
> 함께 사용하지 마세요** — 블로킹 JPA 쪽이 기동에 실패합니다. Spring Boot 4에서는 공존할 수 있습니다.
> Boot 3에서 둘 다 필요하다면 리액티브 영속성 계층과 블로킹 영속성 계층을 별도 모듈로 분리하세요.

## 설치

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    // Spring Boot 3.x
    implementation("io.clroot:hibernate-reactive-coroutines-spring-boot-starter:1.1.0")

    // Spring Boot 4.x
    implementation("io.clroot:hibernate-reactive-coroutines-spring-boot-starter-boot4:1.1.0")

    // DB 드라이버
    implementation("io.vertx:vertx-pg-client:5.1.5")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    // Spring Boot 3.x
    implementation 'io.clroot:hibernate-reactive-coroutines-spring-boot-starter:1.1.0'

    // Spring Boot 4.x
    implementation 'io.clroot:hibernate-reactive-coroutines-spring-boot-starter-boot4:1.1.0'

    // DB 드라이버
    implementation 'io.vertx:vertx-pg-client:5.1.5'
}
```

### Maven

```xml
<!-- Spring Boot 3.x -->
<dependency>
    <groupId>io.clroot</groupId>
    <artifactId>hibernate-reactive-coroutines-spring-boot-starter</artifactId>
    <version>1.1.0</version>
</dependency>

<!-- Spring Boot 4.x -->
<dependency>
    <groupId>io.clroot</groupId>
    <artifactId>hibernate-reactive-coroutines-spring-boot-starter-boot4</artifactId>
    <version>1.1.0</version>
</dependency>
```

## 빠른 시작

### 1. Repository 정의

```kotlin
interface UserRepository : CoroutineCrudRepository<User, Long> {
    suspend fun findByEmail(email: String): User?
    suspend fun findAllByStatus(status: Status): List<User>

    @Query("SELECT u FROM User u WHERE u.role = :role")
    suspend fun findByRole(role: Role): List<User>
}
```

### 2. Service에서 사용

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
}
```

### 3. 설정

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
          pool-size: 10
```

## 문서

| 문서 | 설명 |
|------|------|
| [사용 가이드](docs/usage-guide.ko.md) | 설정, 사용법 및 예제 |
| [마이그레이션](docs/migration.ko.md) | JPA 호환성 및 Spring Data JPA 전환 가이드 |
| [내부 동작](docs/internals.ko.md) | 아키텍처 및 동작 원리 |

## 주의사항

### Lazy Loading

Hibernate Reactive에서는 동기적 Lazy Loading(`parent.children.size`)이 지원되지 않습니다.

```kotlin
// FETCH JOIN 사용 (권장)
@Query("SELECT p FROM Parent p LEFT JOIN FETCH p.children WHERE p.id = :id")
suspend fun findByIdWithChildren(id: Long): Parent?

// 또는 fetch() 메서드 사용
val children = sessionProvider.fetch(parent, Parent::children)
```

### REQUIRES_NEW 미지원

리액티브 환경에서 커넥션 풀 고갈 위험이 있어 지원하지 않습니다.

### 블로킹 호출 탐지 (BlockHound)

`transactional {}` 블록은 Vert.x 이벤트 루프에서 실행되므로, 블록 안의 블로킹 호출 하나
(`Thread.sleep`, 동기 HTTP 클라이언트, 파일 I/O 등)가 해당 루프의 모든 트랜잭션을 멈추게 합니다.
[BlockHound](https://github.com/reactor/BlockHound)로 테스트에서 이런 호출을 잡을 수 있지만,
BlockHound는 "논블로킹으로 표시된 스레드"만 검사하며 Vert.x 이벤트 루프는 기본적으로 표시되지
않습니다. `hibernate-reactive-coroutines-blockhound` 모듈이 이 표시를 자동으로 등록합니다:

```kotlin
dependencies {
    testImplementation("io.clroot:hibernate-reactive-coroutines-blockhound:1.1.0")
}

tasks.withType<Test>().configureEach {
    // JDK 13+에서 BlockHound 런타임 계측에 필요
    jvmArgs("-XX:+AllowRedefinitionToAddDeleteMethods", "-Djdk.attach.allowAttachSelf=true")
}
```

```kotlin
BlockHound.install() // ServiceLoader로 통합이 자동 등록됨

tx.transactional {
    Thread.sleep(100) // BlockingOperationError 발생
}
```

코루틴 내부 동작의 allowlist를 위해 `org.jetbrains.kotlinx:kotlinx-coroutines-debug`와 함께 쓰는
것을 권장합니다. BlockHound는 바이트코드 계측이므로 테스트·로컬 개발에서만 사용하고, 운영 환경
탐지는 Vert.x 내장 blocked-thread checker를 사용하세요.

## 라이선스

MIT License
