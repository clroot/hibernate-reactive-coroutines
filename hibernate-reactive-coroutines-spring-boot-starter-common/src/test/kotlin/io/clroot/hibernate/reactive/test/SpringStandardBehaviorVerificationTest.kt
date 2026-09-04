package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.service.RollbackTestService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.UnexpectedRollbackException

@SpringBootTest(classes = [TestApplication::class, RollbackTestService::class])
class SpringStandardBehaviorVerificationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var rollbackService: RollbackTestService

    init {
        describe("Spring parity: rollback-only with REQUIRED propagation") {
            /**
             * An inner REQUIRED transaction joins the existing transaction. Its exception marks
             * the transaction rollback-only, so catching it in the outer method still causes
             * UnexpectedRollbackException at commit.
             *
             * Spring reference: AbstractPlatformTransactionManager.processCommit()
             * - defStatus.isGlobalRollbackOnly() || (isFailEarlyOnGlobalRollbackOnly() && defStatus.isLocalRollbackOnly())
             */
            it("throws UnexpectedRollbackException when the outer method catches an inner transactional exception") {
                shouldThrow<UnexpectedRollbackException> {
                    rollbackService.outerCatchesInnerException(
                        "spring-standard-outer",
                        "spring-standard-inner",
                    )
                }

                rollbackService.findByName("spring-standard-outer").shouldBeNull()
                rollbackService.findByName("spring-standard-inner").shouldBeNull()
            }

            it("rolls back the outer transaction when an inner exception propagates") {
                shouldThrow<RuntimeException> {
                    rollbackService.outerSaveAndCallInnerThatFails(
                        "propagate-outer",
                        "propagate-inner",
                    )
                }

                rollbackService.findByName("propagate-outer").shouldBeNull()
                rollbackService.findByName("propagate-inner").shouldBeNull()
            }
        }

        describe("Spring parity: automatic RuntimeException rollback") {
            /**
             * Spring rolls back RuntimeException and Error by default. Checked exceptions commit
             * by default, although Kotlin does not distinguish checked exceptions.
             *
             * Spring reference: DefaultTransactionAttribute.rollbackOn(Throwable ex)
             * - return (ex instanceof RuntimeException || ex instanceof Error)
             */
            it("rolls back automatically for RuntimeException") {
                shouldThrow<RuntimeException> {
                    rollbackService.saveAndThrowRuntimeException("runtime-rollback-verify")
                }

                rollbackService.findByName("runtime-rollback-verify").shouldBeNull()
            }

            it("rolls back automatically for IllegalStateException, a RuntimeException subtype") {
                shouldThrow<IllegalStateException> {
                    rollbackService.saveAndThrowIllegalStateException("illegal-state-verify")
                }

                rollbackService.findByName("illegal-state-verify").shouldBeNull()
            }
        }

        describe("Spring parity: rollbackFor and noRollbackFor") {
            /**
             * rollbackFor rolls back configured exception types; noRollbackFor commits them.
             *
             * Spring reference: RuleBasedTransactionAttribute.rollbackOn(Throwable ex)
             */
            it("rolls back exceptions configured with rollbackFor") {
                shouldThrow<java.io.IOException> {
                    rollbackService.saveAndThrowCheckedWithRollbackFor("rollback-for-verify")
                }

                rollbackService.findByName("rollback-for-verify").shouldBeNull()
            }

            it("does not roll back RuntimeException configured with noRollbackFor") {
                shouldThrow<IllegalArgumentException> {
                    rollbackService.saveAndThrowNoRollbackForException("no-rollback-verify")
                }

                val found = rollbackService.findByName("no-rollback-verify")
                found.shouldNotBeNull()
            }
        }

        describe("Spring parity: successful commit") {
            it("commits when the method completes without an exception") {
                val entity = rollbackService.saveSuccessfully("commit-verify", 999)

                entity.id.shouldNotBeNull()
                rollbackService.findById(entity.id!!)?.name shouldBe "commit-verify"
            }
        }
    }
}
