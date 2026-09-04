package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.ReadOnlyTransactionException
import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.TimeoutCancellationException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tests transaction error scenarios.
 *
 * Covers failures that can occur in production.
 */
@SpringBootTest(classes = [TestApplication::class])
class TransactionErrorScenariosTest : IntegrationTestBase() {

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("transaction error scenarios") {

            context("timeouts") {

                it("throws TimeoutCancellationException when a transaction exceeds its timeout") {
                    shouldThrow<TimeoutCancellationException> {
                        tx.transactional(timeout = 100.milliseconds) {
                            testEntityRepository.save(TestEntity(name = "timeout-test", value = 1))
                            // Exceed the configured timeout.
                            kotlinx.coroutines.delay(500)
                            testEntityRepository.save(TestEntity(name = "should-not-save", value = 2))
                        }
                    }
                }

                it("throws TimeoutCancellationException when a read-only transaction exceeds its timeout") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "read-timeout-test", value = 1))
                    }

                    shouldThrow<TimeoutCancellationException> {
                        tx.readOnly(timeout = 100.milliseconds) {
                            testEntityRepository.findAll()
                            kotlinx.coroutines.delay(500)
                            testEntityRepository.count()
                        }
                    }
                }

                it("rolls back the transaction after a timeout") {
                    var savedId: Long? = null

                    shouldThrow<TimeoutCancellationException> {
                        tx.transactional(timeout = 100.milliseconds) {
                            val saved = testEntityRepository.save(TestEntity(name = "timeout-rollback", value = 1))
                            savedId = saved.id
                            kotlinx.coroutines.delay(500)
                        }
                    }

                    // Verify rollback outside the timed-out transaction.
                    val found = tx.readOnly {
                        savedId?.let { testEntityRepository.findById(it) }
                    }
                    found.shouldBeNull()
                }
            }

            context("nested transaction errors") {

                it("propagates an inner exception and rolls back the whole transaction") {
                    var outerEntityId: Long? = null

                    shouldThrow<RuntimeException> {
                        tx.transactional {
                            val outer = testEntityRepository.save(TestEntity(name = "outer-entity", value = 1))
                            outerEntityId = outer.id

                            // REQUIRED reuses the outer transaction.
                            tx.transactional {
                                testEntityRepository.save(TestEntity(name = "inner-entity", value = 2))
                                throw RuntimeException("inner transaction failure")
                            }
                        }
                    }

                    // The outer write must roll back with the shared transaction.
                    val found = tx.readOnly {
                        outerEntityId?.let { testEntityRepository.findById(it) }
                    }
                    found.shouldBeNull()

                    val count = tx.readOnly { testEntityRepository.count() }
                    count shouldBe 0
                }

                it("rolls back the whole transaction when nested read-only work fails") {
                    var savedId: Long? = null

                    shouldThrow<RuntimeException> {
                        tx.transactional {
                            val saved = testEntityRepository.save(TestEntity(name = "nested-readonly-test", value = 1))
                            savedId = saved.id

                            tx.readOnly {
                                testEntityRepository.findById(saved.id!!)
                                throw RuntimeException("failure inside read-only block")
                            }
                        }
                    }

                    val found = tx.readOnly {
                        savedId?.let { testEntityRepository.findById(it) }
                    }
                    found.shouldBeNull()
                }
            }

            context("read-only violations") {

                it("throws ReadOnlyTransactionException when save is attempted in a read-only block") {
                    shouldThrow<ReadOnlyTransactionException> {
                        tx.readOnly {
                            testEntityRepository.save(TestEntity(name = "readonly-violation", value = 1))
                        }
                    }
                }

                it("throws ReadOnlyTransactionException when delete is attempted in a read-only block") {
                    val saved = tx.transactional {
                        testEntityRepository.save(TestEntity(name = "delete-readonly-test", value = 1))
                    }

                    shouldThrow<ReadOnlyTransactionException> {
                        tx.readOnly {
                            testEntityRepository.delete(saved)
                        }
                    }

                    // The rejected delete must not be persisted.
                    val found = tx.readOnly {
                        testEntityRepository.findById(saved.id!!)
                    }
                    found.shouldNotBeNull()
                }

                it("reuses the parent read-write context for nested read-only work") {
                    // readOnly follows REQUIRED semantics and reuses an existing context.

                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "outer-write", value = 1))

                        tx.readOnly {
                            // The parent context remains read-write.
                            testEntityRepository.save(TestEntity(name = "inner-write", value = 2))
                        }
                    }

                    val count = tx.readOnly { testEntityRepository.count() }
                    count shouldBe 2
                }
            }

            context("compound failure scenarios") {

                it("rolls back all saves when the final operation fails") {
                    shouldThrow<RuntimeException> {
                        tx.transactional {
                            repeat(10) { i ->
                                testEntityRepository.save(TestEntity(name = "batch-$i", value = i))
                            }
                            throw RuntimeException("final operation failure")
                        }
                    }

                    val count = tx.readOnly { testEntityRepository.count() }
                    count shouldBe 0
                }

                it("rolls back a failing save-read-update-delete sequence") {
                    val existing = tx.transactional {
                        testEntityRepository.save(TestEntity(name = "complex-sequence", value = 100))
                    }

                    shouldThrow<RuntimeException> {
                        tx.transactional {
                            val newEntity = testEntityRepository.save(TestEntity(name = "new-in-sequence", value = 1))

                            val found = testEntityRepository.findById(existing.id!!)

                            found!!.value = 999
                            testEntityRepository.save(found)

                            testEntityRepository.delete(newEntity)

                            throw RuntimeException("compound sequence failure")
                        }
                    }

                    // All writes in the failed transaction must be discarded.
                    val afterCount = tx.readOnly { testEntityRepository.count() }
                    afterCount shouldBe 1

                    val afterExisting = tx.readOnly { testEntityRepository.findById(existing.id!!) }
                    afterExisting.shouldNotBeNull()
                    afterExisting.value shouldBe 100
                }
            }

            context("exception types") {

                it("rolls back RuntimeException") {
                    shouldThrow<RuntimeException> {
                        tx.transactional {
                            testEntityRepository.save(TestEntity(name = "runtime-ex", value = 1))
                            throw RuntimeException("runtime exception")
                        }
                    }

                    val count = tx.readOnly { testEntityRepository.count() }
                    count shouldBe 0
                }

                it("rolls back IllegalStateException") {
                    shouldThrow<IllegalStateException> {
                        tx.transactional {
                            testEntityRepository.save(TestEntity(name = "illegal-state", value = 1))
                            throw IllegalStateException("invalid state")
                        }
                    }

                    val count = tx.readOnly { testEntityRepository.count() }
                    count shouldBe 0
                }

                it("rolls back IllegalArgumentException") {
                    shouldThrow<IllegalArgumentException> {
                        tx.transactional {
                            testEntityRepository.save(TestEntity(name = "illegal-arg", value = 1))
                            throw IllegalArgumentException("invalid argument")
                        }
                    }

                    val count = tx.readOnly { testEntityRepository.count() }
                    count shouldBe 0
                }
            }
        }
    }
}
