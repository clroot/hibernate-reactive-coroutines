# 사용 가이드

Spring Boot 사용자는 [1장](#1-spring-boot-설정), Ktor 사용자는 [2장](#2-ktor-설정)에서 설정을 마친 뒤
[3장](#3-리포지토리)부터 읽으시면 됩니다. 3장부터는 두 환경에 공통이며, 한쪽에만 해당하는 절은
제목에 표시했습니다.

## 1. Spring Boot 설정

### 1.1 기본 설정

스타터는 `spring.datasource`와 `spring.jpa` 설정을 그대로 읽고, JDBC URL을 Vert.x용 URL로
변환합니다. 라이브러리 고유 설정은 `spring.jpa.properties.hibernate.reactive` 아래에 둡니다.

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

### 1.2 커넥션 풀

| 속성 | 설명 | 기본값 |
| --- | --- | --- |
| `pool-size` | 커넥션 풀 최대 크기 | 10 |
| `connect-timeout` | 커넥션 획득 대기 시간(ms) | Vert.x 기본값 |
| `idle-timeout` | 유휴 커넥션 유지 시간(ms) | Vert.x 기본값 |
| `max-wait-queue-size` | 커넥션 대기 큐 최대 크기 | Vert.x 기본값 |

운영 환경에서는 `connect-timeout`과 `max-wait-queue-size`를 함께 설정하세요. 둘 다 비워 두면 대기
큐가 무제한이라, 데이터베이스가 느려질 때 요청이 실패하지 않고 쌓여 애플리케이션 전체가 멈춥니다.

### 1.3 Vert.x 인스턴스

스타터는 Vert.x 인스턴스를 생성해 `Vertx` 빈으로 노출합니다. 애플리케이션이 `Vertx` 빈을 직접
정의하면 스타터는 그 빈을 재사용하므로, 앱 전체가 하나의 Vert.x를 공유합니다.

아래 설정은 `spring.jpa.properties.hibernate.reactive.vertx` 아래에 두며, 스타터가 인스턴스를
생성할 때만 적용됩니다.

| 속성 | 설명 | 기본값 |
| --- | --- | --- |
| `event-loop-pool-size` | 이벤트 루프 스레드 수 | CPU 코어 수 × 2 |
| `max-event-loop-execute-time` | blocked-thread checker가 경고하는 루프 점유 시간 | 2s |
| `blocked-thread-check-interval` | blocked-thread checker 검사 주기 | 1s |
| `warning-exception-time` | 경고에 스택트레이스를 포함하는 루프 점유 시간 | 5s |

시간 값은 Spring duration 문법(`500ms`, `2s`)을 씁니다. 트랜잭션 블록은 이 이벤트 루프에서
실행되므로, 운영 환경에서는 두 시간 값을 낮춰 두면 블로킹 호출의 위치를 로그에서 바로 찾을 수 있습니다.

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

### 1.4 이벤트 루프 공유

WebFlux에서는 기본적으로 reactor-netty와 Vert.x가 각자 이벤트 루프 풀을 띄웁니다.
`share-event-loops: true`를 설정하면 내장 Netty 서버가 Vert.x 이벤트 루프 위에서 실행되어 HTTP 처리와
데이터베이스 I/O가 스레드 풀을 공유합니다.

```yaml
spring:
  jpa:
    properties:
      hibernate:
        reactive:
          vertx:
            share-event-loops: true
```

요청이 처음부터 Vert.x 스레드에서 시작하므로 트랜잭션 진입 시 스레드 전환이 사라지고, blocked-thread
checker와 BlockHound([6장](#6-블로킹-호출-탐지))가 웹 계층까지 검사합니다. 애플리케이션이
`ReactorResourceFactory` 빈을 직접 정의했다면 그 빈이 우선합니다.

**트레이드오프가 있습니다.** 풀을 분리하면 트랜잭션 안의 블로킹 호출이 데이터베이스 계층만 멈추지만,
루프를 공유하면 같은 실수가 그 루프의 모든 HTTP 커넥션(헬스체크 포함)을 함께 멈춥니다. 켜기 전에
BlockHound로 블로킹 호출이 없음을 검증하고, 운영에서는 blocked-thread checker 임계값을 낮추세요.

### 1.5 SSL

| `ssl-mode` | 동작 |
| --- | --- |
| `disable` | SSL을 사용하지 않습니다(기본값). |
| `allow` | 서버가 요구할 때만 SSL을 사용합니다. |
| `prefer` | SSL을 먼저 시도하고, 실패하면 평문으로 연결합니다. |
| `require` | SSL을 강제하고, JVM 기본 trust store로 인증서를 검증합니다. |
| `verify-ca` | SSL을 강제하고, `trust-certificate`로 지정한 CA 인증서로 검증합니다. |
| `verify-full` | `verify-ca`에 더해 호스트명까지 검증합니다. |

`verify-ca`와 `verify-full`에서는 PEM 형식 CA 인증서 경로인 `trust-certificate`가 필수입니다.
설정이 잘못되면 평문으로 넘어가지 않고 애플리케이션 시작을 중단합니다.

JDBC URL의 `sslmode`와 `currentSchema` 파라미터도 인식합니다. 이 두 값은 Hibernate 설정으로 옮긴
뒤 Vert.x URL에서 제거하고, 나머지 파라미터는 그대로 둡니다.

### 1.6 리포지토리 스캔

스타터는 `@SpringBootApplication` 패키지를 기준으로 `CoroutineCrudRepository`를 상속한 인터페이스를
찾아 빈으로 등록합니다. `@Repository`는 필요 없습니다.

```kotlin
// 기본: @SpringBootApplication 위치를 기준으로 스캔합니다.
@SpringBootApplication
class MyApplication

// 다른 패키지를 지정합니다.
@SpringBootApplication
@EnableHibernateReactiveRepositories(basePackages = ["com.example.repository"])
class MyApplication
```

## 2. Ktor 설정

`io.clroot:hibernate-reactive-coroutines-ktor`와 데이터베이스용 Vert.x 클라이언트를 의존성에
추가합니다. Ktor 3.5를 지원하며 Spring에 대한 런타임 의존성은 없습니다.

### 2.1 플러그인 설치

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

| `database {}` 항목 | 설명 | 기본값 |
| --- | --- | --- |
| `url` | Vert.x 연결 URL (`postgresql://host:port/db`) | 없음 |
| `username`, `password` | 접속 계정 | 없음 |
| `schemaGeneration` | `hibernate.hbm2ddl.auto` 값 | `none` |
| `poolSize` | 커넥션 풀 최대 크기 | 10 |
| `showSql` | SQL 로그 출력 여부 | `false` |
| `property(name, value)` | 그 밖의 Hibernate 속성 | |

`repository()`로 등록한 리포지토리의 엔티티는 Hibernate에도 등록됩니다. 리포지토리가 없는 엔티티만
`entity<T>()`로 따로 등록하세요. 플러그인은 클래스패스를 스캔하지 않습니다.

리소스는 `Application` 확장 프로퍼티로 꺼냅니다.

| 접근자 | 반환 타입 |
| --- | --- |
| `hibernateRepository<R>()` | 등록한 리포지토리 |
| `hibernateTransactionExecutor` | `ReactiveTransactionExecutor` |
| `hibernateSessionProvider` | `ReactiveSessionProvider` |
| `hibernateSessionFactory` | `Mutiny.SessionFactory` |
| `hibernateReactive` | 위 리소스를 모두 담은 `HibernateReactiveResources` |

플러그인은 HTTP 요청을 트랜잭션으로 감싸지 않습니다. 트랜잭션 밖에서 호출한 리포지토리 메서드는 자체
세션을 열고 닫습니다. 여러 작업을 묶으려면 [4.1절](#41-reactivetransactionexecutor)의
`transactional {}`을 사용하세요.

### 2.2 Ktor DI 연동

`io.ktor:ktor-server-di`를 추가하고 `dependencyInjection = true`로 설정하면 리포지토리와
`ReactiveTransactionExecutor`, `ReactiveSessionProvider`, `Vertx`, `HibernateReactiveResources`가
Ktor DI에 등록됩니다.

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

세션 팩토리는 DI에 직접 등록하지 않고 `HibernateReactiveResources.sessionFactory`로만 제공합니다.
Ktor DI가 `AutoCloseable` 값을 자동으로 닫으면서 아래 소유권 규칙을 건너뛰지 않게 하기 위해서입니다.

### 2.3 외부 리소스와 소유권

이미 만든 Vert.x나 세션 팩토리가 있다면 `database {}` 대신 그 객체를 넘깁니다.

```kotlin
install(HibernateReactive) {
    vertx = applicationVertx
    sessionFactory = applicationSessionFactory
    closeExternalVertx = false          // 기본값
    closeExternalSessionFactory = false // 기본값

    repository<UserRepository, User, Long>()
}
```

- 플러그인이 만든 세션 팩토리와 Vert.x는 애플리케이션 종료 시 이 순서로 닫힙니다.
- 외부에서 넘긴 리소스는 `closeExternal...` 플래그를 켠 경우에만 닫습니다.
- 세션 팩토리만 넘기면 그 팩토리의 Vert.x를 자동으로 찾습니다. `vertx`도 함께 지정했다면 같은
  인스턴스여야 하며, 다르면 시작에 실패합니다.
- 커스텀 세션 팩토리가 Hibernate Reactive의 `Implementor` SPI로 Vert.x를 노출하지 않으면 `vertx`를
  직접 지정하세요.

## 3. 리포지토리

### 3.1 통합별 차이

| 항목 | Spring Boot | Ktor |
| --- | --- | --- |
| 상속할 인터페이스 | `org.springframework.data.repository.kotlin.CoroutineCrudRepository` | `io.clroot.hibernate.reactive.repository.CoroutineCrudRepository` |
| 쿼리 어노테이션 | `io.clroot.hibernate.reactive.repository.query.{Query, Param, Modifying}` | 같음 |
| 페이징·정렬 타입 | Spring Data `Pageable`, `Page`, `Slice`, `Sort` | Jakarta Data `PageRequest`, `Page`, `Sort`, `Order` |
| 리포지토리 등록 | 클래스패스 스캔 | `repository<R, T, ID>()` |
| 신규 엔티티 판별 | `Persistable` 우선, 없으면 공통 규칙 | 공통 규칙(`@Version` → ID 값) |
| Auditing | 지원 | 미지원 |

Ktor용 인터페이스는 Jakarta Data `DataRepository`를 확장한 코루틴 계약입니다. 완전한 Jakarta Data
프로바이더는 아니므로 lifecycle 어노테이션, `@Find`, `Limit`, 동기식 인터페이스는 지원하지 않습니다.

### 3.2 기본 CRUD

| 메서드 | 반환 타입 |
| --- | --- |
| `save(entity)` | `T` |
| `saveAll(entities)` | `Flow<T>` |
| `findById(id)` | `T?` |
| `findAll()` | `Flow<T>` |
| `findAllById(ids)` | `Flow<T>` |
| `count()` | `Long` |
| `existsById(id)` | `Boolean` |
| `deleteById(id)`, `delete(entity)`, `deleteAllById(ids)`, `deleteAll()` | `Unit` |

- **`save`는 신규 엔티티를 받은 인스턴스 그대로 persist합니다.** 생성된 ID가 인자에도 반영됩니다.
  기존 엔티티는 merge하므로 반환값을 사용하세요.
- **신규 여부는 `@Version` 값이 null인지로, `@Version`이 없으면 ID가 null이거나 기본값인지로
  판별합니다.** 할당형 ID에 `@Version`도 없다면 Spring에서는 `Persistable`을 구현하세요.
- **삭제는 조회 후 제거합니다.** cascade와 `@PreRemove`가 동작하는 대신 `SELECT`가 한 번 더
  실행됩니다. 대량 삭제는 `@Modifying @Query("DELETE ...")`로 작성하세요.
- **`Flow`는 스트리밍이 아닙니다.** 전체 결과를 메모리에 올린 뒤 방출합니다. 큰 테이블은
  페이지네이션을 사용하세요.
- **ID 타입으로 `@JvmInline` value class를 쓸 수 있습니다.**

### 3.3 파생 쿼리

메서드 이름만으로 쿼리를 만듭니다.

```kotlin
interface UserRepository : CoroutineCrudRepository<User, Long> {
    suspend fun findByEmail(email: String): User?
    suspend fun findByNameAndStatus(name: String, status: Status): User?
    suspend fun findAllByStatus(status: Status): List<User>
    suspend fun findAllByNameContaining(name: String): List<User>
    suspend fun existsByEmail(email: String): Boolean
    suspend fun countByStatus(status: Status): Long
    suspend fun deleteByEmail(email: String): Int   // Unit, Int, Long 중 선택
}
```

| 키워드 | 예시 | 생성되는 조건 |
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

- `Containing`, `StartingWith`, `EndingWith`는 값의 `%`, `_`, `\`를 이스케이프합니다. `Like`는
  패턴을 그대로 넘기므로 이스케이프하지 않습니다.
- `IgnoreCase`는 String 프로퍼티에만 쓸 수 있습니다.
- 엔티티 이름은 JPA 메타모델에서 가져오므로 `@Entity(name = "...")`도 정상 동작합니다.
- 잘못된 메서드 이름, 이름과 인자 개수가 같은 오버로드, `suspend`가 아닌 쿼리 메서드는 기동 시점에
  거부됩니다.

### 3.4 @Query

Spring과 Ktor 모두 이 라이브러리의 어노테이션을 사용합니다.

```kotlin
import io.clroot.hibernate.reactive.repository.query.Modifying
import io.clroot.hibernate.reactive.repository.query.Param
import io.clroot.hibernate.reactive.repository.query.Query
```

```kotlin
interface UserRepository : CoroutineCrudRepository<User, Long> {
    // 이름 파라미터는 Kotlin 파라미터 이름을 그대로 씁니다.
    @Query("SELECT u FROM User u WHERE u.status = :status AND u.role = :role")
    suspend fun findByStatusAndRole(status: Status, role: Role): List<User>

    // 이름을 바꾸려면 @Param을 씁니다.
    @Query("FROM User u WHERE u.status = :s")
    suspend fun findByStatus(@Param("s") status: Status): List<User>

    // 위치 파라미터는 ?1부터 연속이어야 합니다.
    @Query("SELECT u FROM User u WHERE u.age BETWEEN ?1 AND ?2")
    suspend fun findByAgeBetween(minAge: Int, maxAge: Int): List<User>

    // Native SQL은 읽기만 지원합니다.
    @Query("SELECT * FROM users WHERE status = :status", nativeQuery = true)
    suspend fun findNative(status: String): List<User>
}
```

- 이름 파라미터와 위치 파라미터를 한 쿼리에 섞을 수 없고, 번호 없는 `?`는 지원하지 않습니다.
- PostgreSQL 달러 인용 리터럴과 주석은 거부됩니다. Hibernate의 HQL 파서와 Native 파서가 다르게
  처리하기 때문입니다.
- `Sort`나 정렬이 담긴 `Pageable`은 쿼리의 `ORDER BY` 뒤에 덧붙습니다. Native 쿼리는 정렬 파라미터를
  받지 않습니다.

#### 프로젝션

스칼라, 집계, HQL 생성자 표현식을 지원합니다. 인터페이스 프로젝션과 `Tuple`·배열 반환은 지원하지
않습니다.

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

`UPDATE`와 `DELETE`에는 `@Modifying`을 붙입니다. 반환 타입은 `Int`, `Long`, `Unit` 중 하나이며
Native SQL은 쓸 수 없습니다.

```kotlin
@Modifying
@Query("UPDATE User u SET u.status = :newStatus WHERE u.status = :oldStatus")
suspend fun updateStatus(oldStatus: Status, newStatus: Status): Int
```

벌크 업데이트는 세션에 올라온 엔티티를 갱신하지 않습니다. 같은 트랜잭션에서 최신 값을 읽어야 하면
`@Modifying(clearAutomatically = true)`로 세션을 비우세요.

### 3.6 페이지네이션과 정렬

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

| 타입 | COUNT 쿼리 |
| --- | --- |
| Spring `Page` | 실행합니다. |
| Spring `Slice` | 실행하지 않습니다. |
| Jakarta `Page` | `PageRequest.withoutTotal()`이면 생략하며, 이때 `totalElements()`는 예외를 던집니다. |

Jakarta Data는 offset 기반 `PageRequest`만 지원하고 `CursoredPage`는 지원하지 않습니다.

#### COUNT 쿼리 자동 생성

`Page`를 반환하는 `@Query`는 `SELECT u FROM User u ...` 같은 단순 엔티티 쿼리에만 COUNT 쿼리를
자동으로 만듭니다. 다음에 해당하면 `countQuery`를 직접 지정해야 합니다.

- 프로젝션, 조인, `GROUP BY`, `HAVING`, 집합 연산, 후행 `SELECT`
- 쿼리 자체의 페이지 제한, 파라미터가 들어간 `ORDER BY`
- 함수나 경로 탐색이 포함된 정렬식
- `nativeQuery = true`

```kotlin
@Query(
    value = "SELECT u FROM User u LEFT JOIN FETCH u.roles WHERE u.status = :status",
    countQuery = "SELECT COUNT(u) FROM User u WHERE u.status = :status",
)
suspend fun findWithRoles(status: Status, pageable: Pageable): Page<User>
```

### 3.7 Auditing (Spring Boot 전용)

`@CreatedDate`와 `@LastModifiedDate`는 엔티티에 `AuditingEntityListener`를 등록하면 채워집니다.

```kotlin
import io.clroot.hibernate.reactive.spring.boot.auditing.AuditingEntityListener

@Entity
@EntityListeners(AuditingEntityListener::class)
class User(
    @Id @GeneratedValue val id: Long? = null,
    var name: String,
    @CreatedDate var createdAt: Instant? = null,
    @LastModifiedDate var updatedAt: Instant? = null,
)
```

`@CreatedBy`와 `@LastModifiedBy`는 `ReactiveAuditorAware` 빈을 등록하면 채워집니다. WebFlux에서는
ThreadLocal 기반 `SecurityContextHolder`가 비어 있으므로 반드시 `ReactiveSecurityContextHolder`에서
읽어야 합니다.

```kotlin
@Component
class SecurityAuditorAware : ReactiveAuditorAware<String> {
    override suspend fun getCurrentAuditor(): String? =
        ReactiveSecurityContextHolder.getContext()
            .awaitSingleOrNull()
            ?.authentication
            ?.name
}
```

## 4. 트랜잭션

### 4.1 ReactiveTransactionExecutor

두 환경에 공통인 프로그래밍 방식 트랜잭션 API입니다. Spring에서는 빈으로 주입받고, Ktor에서는
`hibernateTransactionExecutor`로 얻습니다.

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

- **`transactional {}`**은 쓰기 트랜잭션을 엽니다. 예외가 나면 롤백하고, 정상 종료하면 flush 후
  커밋합니다. 이미 트랜잭션 안이면 기존 트랜잭션에 참여합니다.
- **`readOnly {}`**는 읽기 전용 세션을 엽니다. dirty checking과 자동 flush를 끄므로 엔티티를
  수정해도 반영되지 않고, 쓰기 메서드를 호출하면 `ReadOnlyTransactionException`이 발생합니다.
- `timeout` 기본값은 30초입니다. 중첩되면 부모의 남은 시간과 비교해 짧은 쪽을 적용합니다.
- 블록은 Vert.x 이벤트 루프에서 실행되지만 MDC, 트레이싱, Reactor context 같은 호출자의 코루틴
  컨텍스트는 유지됩니다.

블록 안에서 자식 코루틴을 분리하거나 블로킹 I/O, 외부 네트워크 호출을 하지 마세요. 블로킹 호출은
[6장](#6-블로킹-호출-탐지)의 BlockHound로 잡아낼 수 있습니다.

### 4.2 @Transactional (Spring Boot 전용)

`@Transactional`을 `suspend` 함수에 그대로 사용합니다. 별도 설정은 필요 없습니다.

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
        // 예외가 발생하면 두 변경이 모두 롤백됩니다.
    }
}
```

`@Transactional` 안에서 `tx.transactional {}`을 호출하면 기존 트랜잭션에 참여합니다. 단,
`readOnly = true` 트랜잭션 안에서 쓰기 트랜잭션을 열면 예외가 발생합니다.

`Propagation.REQUIRES_NEW`의 중첩 호출은 지원하지 않습니다. 부모가 커넥션을 쥔 채 자식이 하나를
더 요구하므로 커넥션 풀이 고갈됩니다. 코드가 거부하지는 않으니 주의하세요. 커밋 후 작업이 필요하면
[4.4절](#44-트랜잭션-이벤트-spring-boot-전용)의 트랜잭션 이벤트를 사용합니다.

### 4.3 트랜잭션 타임아웃 (Spring Boot 전용)

`@Transactional(timeout = ...)`은 트랜잭션 deadline으로 적용됩니다.

PostgreSQL에서는 `SET LOCAL statement_timeout`도 함께 설정하고 리포지토리 작업과 flush 직전마다
남은 시간으로 갱신합니다. 따라서 실행 중인 statement가 deadline을 넘기면 서버가 중단하며, 설정은
트랜잭션이 끝나면 자동으로 사라집니다.

다른 데이터베이스에서는 리포지토리 작업 전후의 deadline 검사만 동작하고, 이미 실행 중인 statement는
끝까지 실행됩니다. Hibernate Reactive에 쿼리 취소 API가 없기 때문입니다.

### 4.4 트랜잭션 이벤트 (Spring Boot 전용)

`@TransactionalEventListener`를 쓰려면 일반 `ApplicationEventPublisher` 대신 스타터가 등록한
reactive `TransactionalEventPublisher`로 이벤트를 발행해야 합니다.

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
        // 커밋에 성공한 뒤에만 실행됩니다.
    }
}
```

`publishEvent`가 반환하는 `Mono`는 반드시 await해야 합니다. 그렇지 않으면 트랜잭션 컨텍스트가 끊겨
after-commit 콜백이 등록되지 않습니다.

## 5. 연관관계 로딩

Hibernate Reactive는 동기 Lazy Loading을 지원하지 않습니다. 초기화되지 않은 연관관계에 접근하면
`HR000069` 오류가 발생하므로 아래 두 방법으로 명시적으로 불러와야 합니다.

### 5.1 FETCH JOIN (권장)

```kotlin
interface ParentRepository : CoroutineCrudRepository<Parent, Long> {
    @Query("SELECT p FROM Parent p LEFT JOIN FETCH p.children WHERE p.id = :id")
    suspend fun findByIdWithChildren(id: Long): Parent?
}
```

### 5.2 fetch() (Spring Boot 전용)

이미 조회한 엔티티의 연관관계는 `TransactionalAwareSessionProvider`로 불러옵니다. 트랜잭션 안에서만
동작합니다.

```kotlin
@Transactional(readOnly = true)
suspend fun getOrderDetails(orderId: Long): Order {
    val order = orders.findById(orderId) ?: error("Order not found")
    sessionProvider.fetchAll(order, Order::items, Order::payments)
    return order
}
```

| 메서드 | 용도 |
| --- | --- |
| `fetch(entity, Entity::property)` | 연관관계 하나를 불러오고 그 값을 반환합니다. |
| `fetchAll(entity, vararg properties)` | 여러 연관관계를 한 번에 불러옵니다. |
| `fetchFromDetached(entity, Entity::class, Entity::property)` | detached 엔티티의 연관관계를 불러옵니다. |

## 6. 블로킹 호출 탐지

트랜잭션 블록은 Vert.x 이벤트 루프에서 실행되므로, 그 안의 블로킹 호출은 같은 루프의 다른 작업을
모두 멈춥니다. `hibernate-reactive-coroutines-blockhound` 모듈은 Vert.x 이벤트 루프 스레드를
[BlockHound](https://github.com/reactor/BlockHound) 검사 대상에 추가합니다. Reactor 기본 통합은
reactor-netty 스레드만 검사하기 때문입니다.

### 6.1 설치

테스트 클래스패스에 추가하면 `ServiceLoader`로 자동 등록됩니다. BlockHound 자체는 이 모듈이 함께
가져옵니다.

```kotlin
dependencies {
    testImplementation("io.clroot:hibernate-reactive-coroutines-blockhound:$hrcVersion")
}

tasks.withType<Test> {
    // JDK 13 이상에서 BlockHound 계측에 필요합니다.
    jvmArgs(
        "-XX:+AllowRedefinitionToAddDeleteMethods",
        "-Djdk.attach.allowAttachSelf=true",
    )
}
```

### 6.2 사용

테스트 시작 시 `BlockHound.install()`을 한 번 호출하면, 이벤트 루프의 블로킹 호출이
`BlockingOperationError`를 던집니다.

```kotlin
class BlockingDetectionTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun installBlockHound() = BlockHound.install()
    }

    @Test
    fun `트랜잭션 블록 안의 블로킹 호출을 잡아낸다`() = runTest {
        assertThrows<BlockingOperationError> {
            tx.transactional {
                Thread.sleep(100)
            }
        }
    }
}
```

BlockHound는 바이트코드 계측이므로 테스트와 개발 환경에서만 사용하세요. 운영 환경에서는
[1.3절](#13-vertx-인스턴스)의 blocked-thread checker로 감시합니다.
