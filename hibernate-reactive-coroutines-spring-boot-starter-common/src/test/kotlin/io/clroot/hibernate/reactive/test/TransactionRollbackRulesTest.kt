package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.service.CustomCheckedException
import io.clroot.hibernate.reactive.test.service.CustomRuntimeException
import io.clroot.hibernate.reactive.test.service.RollbackTestService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.UnexpectedRollbackException
import java.io.IOException

/**
 * Tests Spring's `@Transactional` rollback rules.
 *
 * @see <a href="https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html">
 *     Rolling Back a Declarative Transaction</a>
 *
 * ## Default rules
 * - `RuntimeException` and `Error` roll back by default.
 * - Checked exceptions commit by default.
 * - `rollbackFor` forces a rollback for specified exceptions.
 * - `noRollbackFor` prevents a rollback for specified exceptions.
 */
@SpringBootTest(classes = [TestApplication::class, RollbackTestService::class])
class TransactionRollbackRulesTest : IntegrationTestBase() {

    @Autowired
    private lateinit var rollbackService: RollbackTestService

    init {
        describe("default rollback behavior for RuntimeException") {

            it("rolls back automatically when RuntimeException is thrown") {
                shouldThrow<RuntimeException> {
                    rollbackService.saveAndThrowRuntimeException("runtime-rollback")
                }

                rollbackService.findByName("runtime-rollback").shouldBeNull()
            }

            it("rolls back automatically when IllegalStateException is thrown") {
                shouldThrow<IllegalStateException> {
                    rollbackService.saveAndThrowIllegalStateException("illegal-state-rollback")
                }

                rollbackService.findByName("illegal-state-rollback").shouldBeNull()
            }

            it("commits when it completes normally") {
                val entity = rollbackService.saveSuccessfully("success-commit", 100)

                entity.id.shouldNotBeNull()
                val found = rollbackService.findById(entity.id!!)
                found.shouldNotBeNull()
                found.name shouldBe "success-commit"
            }
        }

        describe("checked exception rollback behavior") {
            /**
             * Spring does not roll back checked exceptions by default.
             *
             * DefaultTransactionAttribute.rollbackOn(Throwable ex):
             *   return (ex instanceof RuntimeException || ex instanceof Error)
             *
             * `IOException` is not a `RuntimeException`, so this transaction commits.
             * Kotlin has no language-level checked exceptions, but Spring applies the
             * same rule based on the JVM exception type.
             */
            it("commits by default when IOException is thrown") {
                shouldThrow<IOException> {
                    rollbackService.saveAndThrowCheckedException("checked-no-rollback")
                }

                val found = rollbackService.findByName("checked-no-rollback")
                found.shouldNotBeNull()
                found.value shouldBe 3
            }
        }

        describe("rollbackFor") {

            it("rolls back a checked exception specified by rollbackFor") {
                shouldThrow<IOException> {
                    rollbackService.saveAndThrowCheckedWithRollbackFor("rollback-for-checked")
                }

                rollbackService.findByName("rollback-for-checked").shouldBeNull()
            }

            it("rolls back a custom exception specified by rollbackFor") {
                shouldThrow<CustomCheckedException> {
                    rollbackService.saveAndThrowCustomCheckedException("custom-checked-rollback")
                }

                rollbackService.findByName("custom-checked-rollback").shouldBeNull()
            }
        }

        describe("noRollbackFor") {

            it("commits a RuntimeException specified by noRollbackFor") {
                shouldThrow<IllegalArgumentException> {
                    rollbackService.saveAndThrowNoRollbackForException("no-rollback-illegal-arg")
                }

                val found = rollbackService.findByName("no-rollback-illegal-arg")
                found.shouldNotBeNull()
                found.value shouldBe 6
            }

            it("commits a custom exception specified by noRollbackFor") {
                shouldThrow<CustomRuntimeException> {
                    rollbackService.saveAndThrowCustomNoRollbackException("custom-no-rollback")
                }

                val found = rollbackService.findByName("custom-no-rollback")
                found.shouldNotBeNull()
                found.value shouldBe 7
            }
        }

        describe("rollback propagation in nested transactions") {

            it("rolls back the outer transaction when the inner transaction fails") {
                // REQUIRED propagation shares the outer transaction.
                shouldThrow<RuntimeException> {
                    rollbackService.outerSaveAndCallInnerThatFails(
                        "outer-propagate",
                        "inner-propagate",
                    )
                }

                rollbackService.findByName("outer-propagate").shouldBeNull()
                rollbackService.findByName("inner-propagate").shouldBeNull()
            }

            it("rolls back when an inner transaction is marked rollback-only even if the outer method catches its exception") {
                // The shared transaction remains rollback-only; commit raises UnexpectedRollbackException.
                shouldThrow<UnexpectedRollbackException> {
                    rollbackService.outerCatchesInnerException(
                        "outer-catch",
                        "inner-caught",
                    )
                }

                rollbackService.findByName("outer-catch").shouldBeNull()
                rollbackService.findByName("inner-caught").shouldBeNull()
            }
        }

        describe("saving multiple entities") {

            it("commits multiple saved entities") {
                val names = listOf("multi-1", "multi-2", "multi-3")

                val saved = rollbackService.saveMultipleSuccessfully(names)
                saved.size shouldBe 3
                saved.forEach { it.id.shouldNotBeNull() }

                saved.forEach { entity ->
                    rollbackService.findById(entity.id!!).shouldNotBeNull()
                }
            }
        }

        describe("data consistency after rollback") {

            it("preserves the previous state after rollback") {
                val existing = rollbackService.saveSuccessfully("existing-before-rollback", 999)
                existing.id.shouldNotBeNull()

                shouldThrow<RuntimeException> {
                    rollbackService.saveAndThrowRuntimeException("should-be-rolled-back")
                }

                rollbackService.findByName("should-be-rolled-back").shouldBeNull()
                rollbackService.findById(existing.id!!)?.name shouldBe "existing-before-rollback"
            }
        }
    }
}
