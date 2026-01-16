# Hibernate Reactive Coroutines

[![](https://jitpack.io/v/clroot/hibernate-reactive-coroutines.svg)](https://jitpack.io/#clroot/hibernate-reactive-coroutines)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.0-blue.svg)](https://kotlinlang.org)
[![Hibernate Reactive](https://img.shields.io/badge/Hibernate%20Reactive-3.1.0-green.svg)](https://hibernate.org/reactive/)
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

**Spring Data JPA 기능 커버리지: ~85-90%** - 자세한 내용은 [JPA 호환성](docs/jpa-compatibility.md) 문서를 참고하세요.

## 설치

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// build.gradle.kts
dependencies {
    // Spring Boot 3.x
    implementation("com.github.clroot.hibernate-reactive-coroutines:hibernate-reactive-coroutines-spring-boot-starter:1.0.0")
    
    // Spring Boot 4.x
    implementation("com.github.clroot.hibernate-reactive-coroutines:hibernate-reactive-coroutines-spring-boot-starter-boot4:1.0.0")
    
    // DB 드라이버
    implementation("io.vertx:vertx-pg-client:4.5.16")
}
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

kotlin:
  hibernate:
    reactive:
      pool-size: 10
```

## 문서

| 문서 | 설명 |
|------|------|
| [사용 가이드](docs/usage-guide.md) | 상세 사용법 및 예제 |
| [설정 레퍼런스](docs/configuration.md) | 모든 설정 옵션 |
| [JPA 호환성](docs/jpa-compatibility.md) | JPA 스펙 지원 및 제약사항 |
| [내부 동작](docs/internals.md) | 아키텍처 및 동작 원리 |
| [마이그레이션](docs/migration.md) | Spring Data JPA에서 전환 가이드 |

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

## 라이선스

MIT License
