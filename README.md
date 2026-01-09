# Hibernate Reactive Coroutines

> Spring Data JPA의 편의성을 Hibernate Reactive + Kotlin Coroutines에서 제공하는 라이브러리

[![](https://jitpack.io/v/clroot/hibernate-reactive-coroutines.svg)](https://jitpack.io/#clroot/hibernate-reactive-coroutines)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-blue.svg)](https://kotlinlang.org)
[![Hibernate Reactive](https://img.shields.io/badge/Hibernate%20Reactive-3.1.0-green.svg)](https://hibernate.org/reactive/)

## 개요

Hibernate Reactive를 사용하면서 Spring Data JPA의 편의성이 그리운 개발자를 위한 라이브러리입니다.

**핵심 가치**: "Hibernate Reactive를 Spring Data JPA처럼 쓰자"

### 지원 환경

- Spring Boot (starter 제공)
- Ktor, Quarkus 등 (core만 사용)

## 모듈 구조

```
hibernate-reactive-coroutines/
├── hibernate-reactive-coroutines/                     # Core 라이브러리 (Spring 없이 사용 가능)
└── hibernate-reactive-coroutines-spring-boot-starter/ # Spring Boot Auto-configuration
```

### 모듈별 기능

| 기능                          | core | spring-boot-starter |
|-----------------------------|:----:|:-------------------:|
| ReactiveSessionContext      |  O   |          O          |
| ReactiveSessionProvider     |  O   |          O          |
| ReactiveTransactionExecutor |  O   |          O          |
| @Entity 자동 스캔               |  X   |          O          |
| application.yml 설정          |  X   |          O          |
| Auto-configuration          |  X   |          O          |

## 설치

### JitPack Repository 추가

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### 의존성 추가

```kotlin
// build.gradle.kts
dependencies {
    // Spring Boot 사용자 (권장)
    implementation("com.github.clroot.hibernate-reactive-coroutines:hibernate-reactive-coroutines-spring-boot-starter:0.1.0")

    // Spring 없이 사용
    implementation("com.github.clroot.hibernate-reactive-coroutines:hibernate-reactive-coroutines:0.1.0")

    // DB 드라이버 (별도 추가 필요)
    implementation("io.vertx:vertx-pg-client:4.5.16")     // PostgreSQL
    // implementation("io.vertx:vertx-mysql-client:4.5.16")  // MySQL
}
```

## 사용법

### 1. ReactiveSessionContext

CoroutineContext Element로 Hibernate Session을 전파합니다.

```kotlin
// 현재 컨텍스트에서 세션 가져오기
val session: Mutiny.Session? = currentSessionOrNull()
val context: ReactiveSessionContext? = currentContextOrNull()

// 트랜잭션 모드 확인
val isReadOnly = context?.isReadOnly ?: false
```

### 2. ReactiveSessionProvider

Persistence Adapter에서 사용하는 read/write 헬퍼입니다.

```kotlin
@Component
class UserPersistenceAdapter(
    private val sessions: ReactiveSessionProvider,
) : LoadUserPort, SaveUserPort {

    // 읽기 작업 - 컨텍스트 있으면 재사용, 없으면 새 세션
    override suspend fun findById(id: UserId): User? =
        sessions.read { session ->
            session.find(UserEntity::class.java, id.value)
        }?.let { mapper.toDomain(it) }

    // 쓰기 작업 - ReadOnly 컨텍스트면 예외 발생
    override suspend fun save(user: User): User =
        sessions.write { session ->
            val entity = mapper.toEntity(user)
            session.persist(entity).map { entity }
        }.let { mapper.toDomain(it) }
}
```

### 3. ReactiveTransactionExecutor

Service 레이어에서 여러 Port 호출을 하나의 트랜잭션으로 묶을 때 사용합니다.

```kotlin
@Service
class OrderService(
    private val tx: ReactiveTransactionExecutor,
    private val orderPort: SaveOrderPort,
    private val inventoryPort: UpdateInventoryPort,
    private val paymentPort: ProcessPaymentPort,
) {
    // 쓰기 트랜잭션 - 여러 작업을 원자적으로 수행
    suspend fun placeOrder(command: PlaceOrderCommand): Order = tx.transactional {
        val order = orderPort.save(Order.create(command))
        inventoryPort.decrease(command.productId, command.quantity)
        paymentPort.process(order.id, command.amount)
        order
        // 예외 발생 시 모두 롤백
    }

    // 읽기 전용 세션
    suspend fun getOrderHistory(userId: Long): List<Order> = tx.readOnly {
        orderPort.findAllByUserId(userId)
    }

    // 타임아웃 지정
    suspend fun processLongRunningTask() = tx.transactional(timeout = 60.seconds) {
        // 오래 걸리는 작업
    }
}
```

**특징**:

- 중첩 호출 시 기존 세션 재사용 (REQUIRED 동작)
- 타임아웃 상속 (중첩 시 부모의 남은 시간과 비교)
- ReadOnly 모드에서 write 시도 시 `ReadOnlyTransactionException` 발생

## Spring Boot 설정

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

kotlin:
  hibernate:
    reactive:
      pool-size: 10  # 커넥션 풀 크기 (기본값: 10)
```

### Auto-configuration 제공 항목

- `Mutiny.SessionFactory` 빈 자동 등록
- `@Entity` 클래스 자동 스캔
- `ReactiveSessionProvider`, `ReactiveTransactionExecutor` 빈 등록

## 테스트

### Vert.x 스레드 일관성

Hibernate Reactive 세션은 Vert.x EventLoop 스레드에서 열리지만, Kotlin Coroutines는 다른 스레드에서 실행될 수 있습니다. 이 라이브러리는 내부적으로
`vertx-lang-kotlin-coroutines`를 사용하여 이 문제를 해결합니다.

### 테스트 예시

```kotlin
@SpringBootTest
class MyIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("ReactiveTransactionExecutor") {
            context("transactional 블록") {
                it("여러 write 작업이 원자적으로 수행된다") {
                    tx.transactional {
                        // 여러 write 작업
                    }
                }

                it("예외 발생 시 모든 변경이 롤백된다") {
                    var savedId: Long? = null

                    shouldThrow<RuntimeException> {
                        tx.transactional {
                            val saved = savePort.save(entity)
                            savedId = saved.id
                            throw RuntimeException("의도적 롤백")
                        }
                    }

                    savedId.shouldNotBeNull()
                    loadPort.findById(savedId).shouldBeNull()
                }
            }
        }
    }
}
```

## 의존성

### Core 모듈

| 라이브러리              | 버전          | 설명               |
|--------------------|-------------|------------------|
| Hibernate Reactive | 3.1.0.Final | 리액티브 ORM         |
| Mutiny             | 2.6.0       | Reactive Streams |
| Vert.x             | 4.5.16      | 이벤트 루프           |
| Kotlin Coroutines  | 1.10.2      | 코루틴 지원           |

### Spring Boot Starter

Core 모듈의 모든 의존성 + Spring Boot Auto-configuration

## 주의사항

### REQUIRES_NEW 미지원

리액티브 환경에서 REQUIRES_NEW는 새 DB 커넥션을 필요로 하며, 중첩 시 커넥션 풀 고갈 위험이 있어 지원하지 않습니다.

**대안**:

- 이벤트 기반 분리
- 별도 코루틴/채널로 비동기 처리
- 서비스 분리 또는 메시지 큐

## 향후 계획

- [ ] Repository 인터페이스 자동 구현 (Spring Data JPA 스타일)
- [ ] 쿼리 메서드 자동 생성 (`findByEmail`, `existsByEmail` 등)
- [ ] Pagination 지원 (`ReactivePage<T>`, `Pageable`)
- [ ] @Query 어노테이션 (커스텀 JPQL/네이티브 쿼리)
- [ ] Auditing (`@CreatedDate`, `@LastModifiedDate`)

## 참고 자료

- [Hibernate Reactive 공식 문서](https://hibernate.org/reactive/)
- [Vert.x Kotlin Coroutines](https://vertx.io/docs/vertx-lang-kotlin-coroutines/kotlin/)
- [Mutiny - Reactive Streams](https://smallrye.io/smallrye-mutiny/)

## 라이선스

MIT License
