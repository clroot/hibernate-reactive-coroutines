package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.service.PropagationTestService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.IllegalTransactionStateException

/**
 * Tests Spring @Transactional propagation options.
 *
 * Verifies the behavior of each Propagation option.
 */
@SpringBootTest(classes = [TestApplication::class, PropagationTestService::class])
class TransactionPropagationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var propagationService: PropagationTestService

    init {
        describe("Propagation.REQUIRED (default)") {

            context("when an existing transaction is present") {

                it("joins the existing transaction") {
                    val (outer, inner) = propagationService.nestedRequiredBothCommit(
                        "outer-required",
                        "inner-required",
                    )

                    outer.id.shouldNotBeNull()
                    inner.id.shouldNotBeNull()

                    propagationService.findById(outer.id!!).shouldNotBeNull()
                    propagationService.findById(inner.id!!).shouldNotBeNull()
                }

                it("rolls back the outer transaction when the inner transaction fails") {
                    shouldThrow<RuntimeException> {
                        propagationService.outerRequired("outer-fail") {
                            propagationService.innerRequiredWithException("inner-fail")
                        }
                    }

                    // REQUIRED shares the transaction, so both writes must roll back.
                    propagationService.findByName("outer-outer-fail").shouldBeNull()
                    propagationService.findByName("inner-fail-inner-fail").shouldBeNull()
                }
            }

            context("when no transaction is present") {

                it("starts a new transaction") {
                    val entity = propagationService.innerRequired("new-tx")

                    entity.id.shouldNotBeNull()
                    propagationService.findById(entity.id!!).shouldNotBeNull()
                }
            }
        }

        describe("Propagation.REQUIRES_NEW") {

            it("runs in a new transaction when called independently") {
                // Nested REQUIRES_NEW calls can exhaust the Hibernate Reactive connection pool.

                val entity = propagationService.requiresNewTransaction("standalone")
                entity.id.shouldNotBeNull()

                propagationService.findByName("requires-new-standalone").shouldNotBeNull()
            }
        }

        describe("Propagation.SUPPORTS") {

            context("when an existing transaction is present") {

                it("joins the existing transaction") {
                    val saved = propagationService.saveEntity("supports-existing", 100)

                    val found = propagationService.supportsReadOnly(saved.id!!)

                    found.shouldNotBeNull()
                    found.name shouldBe "supports-existing"
                }
            }

            context("when no transaction is present") {

                it("runs without a transaction") {
                    val entity = propagationService.supportsWithTransaction("supports-no-tx")
                    entity.id.shouldNotBeNull()

                    propagationService.findById(entity.id!!).shouldNotBeNull()
                }
            }
        }

        describe("Propagation.NOT_SUPPORTED") {

            it("suspends the existing transaction") {
                val entity = propagationService.notSupportedAction("not-supported")
                entity.id.shouldNotBeNull()

                propagationService.findById(entity.id!!).shouldNotBeNull()
            }
        }

        describe("Propagation.MANDATORY") {

            context("when no transaction is present") {

                it("throws IllegalTransactionStateException") {
                    val exception = shouldThrow<IllegalTransactionStateException> {
                        propagationService.mandatoryAction("mandatory-no-tx")
                    }

                    exception.message shouldContain "No existing transaction found"
                }
            }
        }

        describe("Propagation.NEVER") {

            context("when an existing transaction is present") {

                it("throws IllegalTransactionStateException") {
                    val exception = shouldThrow<IllegalTransactionStateException> {
                        propagationService.outerRequired("outer-for-never") {
                            propagationService.neverAction("never-inside-tx")
                        }
                    }

                    exception.message shouldContain "Existing transaction found"
                }
            }

            context("when no transaction is present") {

                it("runs without a transaction") {
                    val entity = propagationService.neverAction("never-no-tx")
                    entity.id.shouldNotBeNull()
                }
            }
        }

        describe("readOnly=true") {

            context("read operations") {

                it("succeeds") {
                    val saved = propagationService.saveEntity("readonly-read", 500)

                    val found = propagationService.supportsReadOnly(saved.id!!)

                    found.shouldNotBeNull()
                    found.name shouldBe "readonly-read"
                }
            }

            context("write attempts") {

                it("throws ReadOnlyTransactionException for a write") {
                    shouldThrow<io.clroot.hibernate.reactive.ReadOnlyTransactionException> {
                        propagationService.readOnlyWriteAttempt("readonly-write-test")
                    }

                    // The failed write must be rolled back.
                    val found = propagationService.findByName("readonly-write-readonly-write-test")
                    found.shouldBeNull()
                }
            }
        }

        describe("nested transaction scenarios") {

            it("commits nested REQUIRED calls") {
                val (outer, inner) = propagationService.nestedRequiredBothCommit(
                    "nested-outer-commit",
                    "nested-inner-commit",
                )

                outer.id.shouldNotBeNull()
                inner.id.shouldNotBeNull()

                propagationService.findByName("nested-outer-commit")?.name shouldBe "nested-outer-commit"
                propagationService.findByName("inner-nested-inner-commit")?.name shouldBe "inner-nested-inner-commit"
            }

            it("rolls back nested REQUIRED calls when the inner call fails") {
                shouldThrow<RuntimeException> {
                    propagationService.nestedRequiredInnerFails(
                        "nested-outer-rollback",
                        "nested-inner-rollback",
                    )
                }

                // REQUIRED shares one transaction, so both writes must roll back.
                propagationService.findByName("nested-outer-rollback").shouldBeNull()
                propagationService.findByName("inner-fail-nested-inner-rollback").shouldBeNull()
            }
        }
    }
}
