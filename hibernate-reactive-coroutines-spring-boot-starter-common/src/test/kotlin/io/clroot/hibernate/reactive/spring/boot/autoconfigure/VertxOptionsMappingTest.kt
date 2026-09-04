package io.clroot.hibernate.reactive.spring.boot.autoconfigure

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.vertx.core.VertxOptions
import java.time.Duration
import java.util.concurrent.TimeUnit

class VertxOptionsMappingTest : DescribeSpec({

    describe("buildVertxOptions") {

        it("retains Vert.x defaults for unspecified values") {
            val defaults = VertxOptions()

            val options = buildVertxOptions(HibernateReactiveProperties.VertxSettings())

            options.eventLoopPoolSize shouldBe defaults.eventLoopPoolSize
            options.maxEventLoopExecuteTime shouldBe defaults.maxEventLoopExecuteTime
            options.maxEventLoopExecuteTimeUnit shouldBe defaults.maxEventLoopExecuteTimeUnit
            options.blockedThreadCheckInterval shouldBe defaults.blockedThreadCheckInterval
            options.warningExceptionTime shouldBe defaults.warningExceptionTime
        }

        it("maps configured durations to nanoseconds") {
            val options = buildVertxOptions(
                HibernateReactiveProperties.VertxSettings(
                    eventLoopPoolSize = 4,
                    maxEventLoopExecuteTime = Duration.ofMillis(500),
                    blockedThreadCheckInterval = Duration.ofSeconds(1),
                    warningExceptionTime = Duration.ofSeconds(2),
                ),
            )

            options.eventLoopPoolSize shouldBe 4
            options.maxEventLoopExecuteTime shouldBe 500_000_000L
            options.maxEventLoopExecuteTimeUnit shouldBe TimeUnit.NANOSECONDS
            options.blockedThreadCheckInterval shouldBe 1_000_000_000L
            options.blockedThreadCheckIntervalUnit shouldBe TimeUnit.NANOSECONDS
            options.warningExceptionTime shouldBe 2_000_000_000L
            options.warningExceptionTimeUnit shouldBe TimeUnit.NANOSECONDS
        }
    }
})
