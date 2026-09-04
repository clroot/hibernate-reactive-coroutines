package io.clroot.hibernate.reactive.spring.boot.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Hibernate Reactive-specific properties.
 *
 * Connection and JPA settings remain under `spring.datasource.*` and `spring.jpa.*`.
 *
 * ```yaml
 * spring:
 *   jpa:
 *     properties:
 *       hibernate:
 *         reactive:
 *           pool-size: 10
 *           ssl-mode: disable
 * ```
 */
@ConfigurationProperties(prefix = "spring.jpa.properties.hibernate.reactive")
internal data class HibernateReactiveProperties(
    /**
     * Hibernate Reactive connection-pool size. Defaults to 10.
     *
     * Hibernate Reactive uses its own pool rather than JDBC's HikariCP.
     */
    val poolSize: Int = 10,

    /**
     * Vert.x PostgreSQL client SSL mode. Defaults to `disable`.
     *
     * Supported values are `disable`, `allow`, `prefer`, `require`, `verify-ca`, and `verify-full`.
     *
     * Production deployments should use `verify-full` with a configured CA certificate.
     */
    val sslMode: String = "disable",

    /**
     * Maximum time in milliseconds to wait for a pool connection.
     *
     * A request that cannot acquire a connection within this duration times out. `null` uses the
     * Vert.x default.
     *
     * Set this together with [maxWaitQueueSize] in production to bound overload when the database
     * becomes slow.
     *
     * @see org.hibernate.reactive.provider.Settings.POOL_CONNECT_TIMEOUT
     */
    val connectTimeout: Int? = null,

    /**
     * Maximum idle connection lifetime in milliseconds.
     *
     * Unused connections are removed after this duration. `null` uses the Vert.x default.
     *
     * @see org.hibernate.reactive.provider.Settings.POOL_IDLE_TIMEOUT
     */
    val idleTimeout: Int? = null,

    /**
     * Maximum number of requests waiting for a pool connection.
     *
     * Requests fail immediately when the queue is full. `null` uses Vert.x's unbounded default.
     *
     * Set this together with [connectTimeout] in production to prevent unbounded request buildup
     * during database latency.
     *
     * @see org.hibernate.reactive.provider.Settings.POOL_MAX_WAIT_QUEUE_SIZE
     */
    val maxWaitQueueSize: Int? = null,

    /**
     * Settings for the Vert.x instance created by this starter.
     *
     * These settings do not apply when the application provides its own `Vertx` bean.
     */
    val vertx: VertxSettings = VertxSettings(),
) {
    /**
     * Event-loop and blocked-thread checker settings for Hibernate Reactive's Vert.x instance.
     *
     * `transactional {}` runs on an event loop. Lower checker thresholds expose blocking calls or
     * CPU monopolization sooner in production logs.
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
         * Event-loop thread count. Defaults to Vert.x's default (twice the CPU count).
         *
         * When Vert.x is dedicated to database I/O, it can be smaller than the CPU count to reduce
         * thread use.
         */
        val eventLoopPoolSize: Int? = null,

        /**
         * Maximum continuous event-loop execution time. Defaults to Vert.x's default (2 seconds).
         *
         * Exceeding it produces a blocked-thread checker warning.
         */
        val maxEventLoopExecuteTime: Duration? = null,

        /**
         * Blocked-thread checker interval. Defaults to Vert.x's default (1 second).
         */
        val blockedThreadCheckInterval: Duration? = null,

        /**
         * Include a stack trace in warnings after this duration. Defaults to Vert.x's default
         * (5 seconds).
         *
         * Lower it to identify code blocking an event loop sooner.
         */
        val warningExceptionTime: Duration? = null,

        /**
         * Run the embedded Netty reactive web server (WebFlux) on this Vert.x event-loop group.
         * Defaults to `false`.
         *
         * Sharing avoids a second reactor-netty event-loop pool but weakens fault isolation: a
         * blocking call in `transactional {}` can also stall HTTP serving, including health checks.
         *
         * Verify blocking-call safety with `hibernate-reactive-coroutines-blockhound` and use
         * conservative blocked-thread checker thresholds in production.
         */
        val shareEventLoops: Boolean = false,
    )
}
