# 설정 레퍼런스

## application.yml

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
          ssl-mode: disable
          connect-timeout: 30000
          idle-timeout: 600000
          max-wait-queue-size: 100
```

## 설정 속성

### 커넥션 풀

| 속성                  | 설명                       | 기본값        |
| --------------------- | -------------------------- | ------------- |
| `pool-size`           | 커넥션 풀 최대 크기        | 10            |
| `connect-timeout`     | 커넥션 획득 대기 시간 (ms) | Vert.x 기본값 |
| `idle-timeout`        | 유휴 커넥션 유지 시간 (ms) | Vert.x 기본값 |
| `max-wait-queue-size` | 대기 큐 최대 크기          | Vert.x 기본값 |

### SSL

| 속성       | 설명          | 기본값  |
| ---------- | ------------- | ------- |
| `ssl-mode` | SSL 연결 모드 | disable |

**SSL 모드 옵션:**

| 모드          | 설명                        |
| ------------- | --------------------------- |
| `disable`     | SSL 사용 안함               |
| `allow`       | 서버가 요구하면 SSL 사용    |
| `prefer`      | SSL 시도, 실패 시 비암호화  |
| `require`     | SSL 필수 (인증서 검증 안함) |
| `verify-ca`   | SSL + CA 인증서 검증        |
| `verify-full` | SSL + CA + 호스트명 검증    |

## Repository 스캔

기본적으로 `@SpringBootApplication` 위치를 기준으로 스캔합니다.

```kotlin
// 기본 동작
@SpringBootApplication
class MyApplication

// 커스텀 패키지 지정
@SpringBootApplication
@EnableHibernateReactiveRepositories(basePackages = ["com.example.repository"])
class MyApplication
```

## Auto-configuration

Spring Boot Starter가 자동으로 등록하는 빈:

- `Mutiny.SessionFactory`
- `ReactiveSessionProvider`
- `ReactiveTransactionExecutor`
- `TransactionalAwareSessionProvider`
- `HibernateReactiveTransactionManager`
- Repository 프록시 구현체들
