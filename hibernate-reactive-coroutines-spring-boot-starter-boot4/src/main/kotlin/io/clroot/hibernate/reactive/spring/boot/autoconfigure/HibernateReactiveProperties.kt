package io.clroot.hibernate.reactive.spring.boot.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Hibernate Reactive 전용 설정 프로퍼티.
 *
 * 대부분의 설정은 기존 Spring 프로퍼티를 그대로 사용합니다:
 * - `spring.datasource.*`: DB 연결 정보
 * - `spring.jpa.*`: JPA/Hibernate 설정
 *
 * 이 프로퍼티는 Hibernate Reactive 전용 설정만 포함합니다:
 *
 * ```yaml
 * spring:
 *   jpa:
 *     properties:
 *       hibernate:
 *         reactive:
 *           pool-size: 10  # 커넥션 풀 사이즈
 *           ssl-mode: disable  # SSL 모드 (disable, allow, prefer, require, verify-ca, verify-full)
 * ```
 */
@ConfigurationProperties(prefix = "spring.jpa.properties.hibernate.reactive")
data class HibernateReactiveProperties(
    /**
     * Hibernate Reactive 커넥션 풀 사이즈 (기본값: 10)
     *
     * 일반 JDBC의 HikariCP와 달리, Hibernate Reactive는 자체 커넥션 풀을 사용합니다.
     */
    val poolSize: Int = 10,

    /**
     * Vert.x PG Client SSL 모드 (기본값: disable)
     *
     * 가능한 값:
     * - `disable`: SSL 사용 안함
     * - `allow`: 서버가 요구하면 SSL 사용
     * - `prefer`: SSL 시도, 실패 시 비암호화
     * - `require`: SSL 필수 (인증서 검증 안함)
     * - `verify-ca`: SSL + CA 인증서 검증
     * - `verify-full`: SSL + CA + 호스트명 검증
     *
     * AWS RDS 연결 시 `require` 권장
     */
    val sslMode: String = "disable",

    /**
     * 커넥션 풀에서 커넥션 요청 시 최대 대기 시간 (밀리초)
     *
     * 이 시간 내에 커넥션을 획득하지 못하면 타임아웃 예외가 발생합니다.
     * null이면 Vert.x 기본값 사용
     *
     * @see org.hibernate.reactive.provider.Settings.POOL_CONNECT_TIMEOUT
     */
    val connectTimeout: Int? = null,

    /**
     * 유휴 커넥션의 최대 유지 시간 (밀리초)
     *
     * 이 시간 동안 사용되지 않은 커넥션은 풀에서 제거됩니다.
     * null이면 Vert.x 기본값 사용
     *
     * @see org.hibernate.reactive.provider.Settings.POOL_IDLE_TIMEOUT
     */
    val idleTimeout: Int? = null,

    /**
     * 커넥션 풀 대기 큐의 최대 크기
     *
     * 모든 커넥션이 사용 중일 때 대기할 수 있는 최대 요청 수입니다.
     * 대기 큐가 가득 차면 즉시 예외가 발생합니다.
     * null이면 Vert.x 기본값 사용
     *
     * @see org.hibernate.reactive.provider.Settings.POOL_MAX_WAIT_QUEUE_SIZE
     */
    val maxWaitQueueSize: Int? = null,
)
