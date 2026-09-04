package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.service.PropagationTestService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.TransactionTimedOutException
import org.springframework.transaction.UnexpectedRollbackException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Tests Spring's `@Transactional` timeout property.
 *
 * @see <a href="https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html">
 *     Using @Transactional - timeout</a>
 *
 * ## Default behavior
 * - `timeout` specifies the maximum transaction duration in seconds.
 * - The default is `-1`, which delegates to the underlying transaction system.
 * - Expiration raises `TransactionTimedOutException`.
 *
 * A transaction that exceeds its deadline must not commit and must report Spring's
 * standard timeout exception.
 */
@SpringBootTest(
    classes = [TestApplication::class, PropagationTestService::class],
    properties = ["spring.jpa.properties.hibernate.reactive.pool-size=1"],
)
class TransactionTimeoutTest : IntegrationTestBase() {

    @Autowired
    private lateinit var propagationService: PropagationTestService

    init {
        describe("timeout configuration") {
            context("work within the timeout") {
                it("completes a fast operation with timeout=10") {
                    val entity = propagationService.transactionWithLongTimeout("timeout-ok")

                    entity.id.shouldNotBeNull()
                    propagationService.findByName("timeout-ok").shouldNotBeNull()
                }
            }

            context("timeout expiration") {
                it("rolls back a transaction that exceeds timeout=1") {
                    val error = shouldThrow<UnexpectedRollbackException> {
                        propagationService.transactionWithShortTimeout("timeout-expired", 1_500)
                    }
                    error.mostSpecificCause.shouldBeInstanceOf<TransactionTimedOutException>()

                    propagationService.findByName("timeout-expired").shouldBeNull()
                }

                it("cancels an in-flight PostgreSQL query at the deadline and returns its connection") {
                    val started = TimeSource.Monotonic.markNow()

                    shouldThrow<TransactionTimedOutException> {
                        propagationService.transactionWithSlowQuery("timeout-in-flight")
                    }

                    started.elapsedNow() shouldBeLessThan 3.seconds
                    propagationService.findByName("timeout-in-flight").shouldBeNull()
                    propagationService.currentStatementTimeout() shouldBe "0"
                }

                it("rejects repository calls after the deadline before executing them") {
                    shouldThrow<TransactionTimedOutException> {
                        propagationService.repositoryCallAfterTimeout("timeout-before-query", 1_100)
                    }

                    propagationService.findByName("timeout-before-query").shouldBeNull()
                }

                it("does not commit when the caller catches the timeout exception") {
                    val error = shouldThrow<UnexpectedRollbackException> {
                        propagationService.catchRepositoryTimeout("timeout-caught", 1_100)
                    }
                    error.mostSpecificCause.shouldBeInstanceOf<TransactionTimedOutException>()

                    propagationService.findByName("timeout-caught").shouldBeNull()
                }
            }
        }
    }
}
