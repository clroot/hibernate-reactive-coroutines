package io.clroot.hibernate.reactive.spring.boot.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

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
public data class HibernateReactiveProperties(
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
     * - `require`: SSL 필수 (기본 trust store로 인증서 검증)
     * - `verify-ca`: SSL + 지정한 CA 인증서 검증
     * - `verify-full`: SSL + 지정한 CA + 호스트명 검증
     *
     * 프로덕션에서는 CA 인증서를 지정한 `verify-full` 권장
     */
    val sslMode: String = "disable",

    /**
     * 커넥션 풀에서 커넥션 요청 시 최대 대기 시간 (밀리초)
     *
     * 이 시간 내에 커넥션을 획득하지 못하면 타임아웃 예외가 발생합니다.
     * null이면 Vert.x 기본값 사용
     *
     * **프로덕션 권장**: [maxWaitQueueSize]와 함께 반드시 설정하세요.
     * 둘 다 설정하지 않으면 대기 큐가 무제한이라, DB가 느려질 때 요청이 빠르게 실패하지 않고
     * 계속 쌓여 애플리케이션 전체가 멈추는 브라운아웃으로 이어집니다.
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
     * null이면 Vert.x 기본값(무제한) 사용
     *
     * **프로덕션 권장**: 무제한 대기 큐는 DB 지연 시 요청이 무한정 쌓이게 하므로
     * [connectTimeout]과 함께 명시적으로 설정하세요.
     *
     * @see org.hibernate.reactive.provider.Settings.POOL_MAX_WAIT_QUEUE_SIZE
     */
    val maxWaitQueueSize: Int? = null,

    /**
     * 스타터가 생성하는 Vert.x 인스턴스 설정.
     *
     * 애플리케이션에 `Vertx` 빈이 이미 있으면 그 빈이 사용되며 이 설정은 무시됩니다.
     */
    val vertx: VertxSettings = VertxSettings(),
) {
    /**
     * Hibernate Reactive가 사용할 Vert.x 인스턴스의 이벤트 루프·blocked-thread checker 설정.
     *
     * `transactional {}` 블록은 이벤트 루프에서 실행되므로, blocked-thread checker 임계값을
     * 낮게 잡을수록 블록 안의 실수(블로킹 호출, CPU 독점)를 운영 로그에서 빨리 발견할 수 있습니다.
     *
     * ```yaml
     * spring:
     *   jpa:
     *     properties:
     *       hibernate:
     *         reactive:
     *           vertx:
     *             event-loop-pool-size: 4
     *             max-event-loop-execute-time: 500ms
     *             warning-exception-time: 2s
     * ```
     */
    public data class VertxSettings(
        /**
         * 이벤트 루프 스레드 수 (기본값: Vert.x 기본값, 2 × CPU 코어)
         *
         * DB I/O 전용 Vert.x이므로 웹 서버와 별도로 뜨는 환경에서는
         * 코어 수보다 작게 잡아 스레드 수를 줄일 수 있습니다.
         */
        val eventLoopPoolSize: Int? = null,

        /**
         * 이벤트 루프가 한 번에 점유할 수 있는 최대 시간 (기본값: Vert.x 기본값, 2초)
         *
         * 초과 시 blocked-thread checker가 경고 로그를 남깁니다.
         */
        val maxEventLoopExecuteTime: Duration? = null,

        /**
         * blocked-thread checker의 검사 주기 (기본값: Vert.x 기본값, 1초)
         */
        val blockedThreadCheckInterval: Duration? = null,

        /**
         * 이 시간 이상 루프가 점유되면 경고 로그에 스택트레이스를 포함합니다
         * (기본값: Vert.x 기본값, 5초)
         *
         * 어떤 코드가 루프를 막았는지 추적하려면 이 값을 낮추세요.
         */
        val warningExceptionTime: Duration? = null,
    )
}
