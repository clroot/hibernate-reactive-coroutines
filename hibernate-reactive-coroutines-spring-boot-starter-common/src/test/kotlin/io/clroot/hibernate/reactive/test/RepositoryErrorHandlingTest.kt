package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(classes = [TestApplication::class])
class RepositoryErrorHandlingTest : IntegrationTestBase() {

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("Repository error handling") {

            context("transaction rollback") {

                it("rolls back the transaction when an exception follows save") {
                    var savedId: Long? = null

                    shouldThrow<RuntimeException> {
                        tx.transactional {
                            val entity = TestEntity(name = "rollback-test", value = 100)
                            val saved = testEntityRepository.save(entity)
                            savedId = saved.id
                            throw RuntimeException("intentional rollback")
                        }
                    }

                    savedId.shouldNotBeNull()

                    val found = tx.readOnly {
                        testEntityRepository.findById(savedId!!)
                    }
                    found.shouldBeNull()
                }

                it("rolls back all work when an exception follows multiple saves") {
                    shouldThrow<RuntimeException> {
                        tx.transactional {
                            testEntityRepository.save(TestEntity(name = "multi-rollback-1", value = 1))
                            testEntityRepository.save(TestEntity(name = "multi-rollback-2", value = 2))
                            testEntityRepository.save(TestEntity(name = "multi-rollback-3", value = 3))
                            throw RuntimeException("rollback all work")
                        }
                    }

                    val afterCount = tx.readOnly { testEntityRepository.count() }
                    afterCount shouldBe 0
                }

                it("rolls back a deletion when an exception follows delete") {
                    val saved = tx.transactional {
                        testEntityRepository.save(TestEntity(name = "delete-rollback", value = 999))
                    }

                    shouldThrow<RuntimeException> {
                        tx.transactional {
                            testEntityRepository.delete(saved)
                            throw RuntimeException("rollback deletion")
                        }
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findById(saved.id!!)
                    }
                    found.shouldNotBeNull()
                    found.name shouldBe "delete-rollback"
                }
            }

            context("exception propagation") {

                it("propagates repository exceptions to the caller") {
                    val exception = shouldThrow<IllegalStateException> {
                        tx.transactional {
                            testEntityRepository.save(TestEntity(name = "exception-test", value = 1))
                            throw IllegalStateException("custom exception")
                        }
                    }

                    exception.message shouldBe "custom exception"
                }
            }

            context("transaction boundaries") {

                it("does not roll back changes from a completed transaction") {
                    val outsideTx = tx.transactional {
                        testEntityRepository.save(TestEntity(name = "outside-tx", value = 100))
                    }

                    shouldThrow<RuntimeException> {
                        tx.transactional {
                            testEntityRepository.save(TestEntity(name = "inside-tx", value = 200))
                            throw RuntimeException("rollback")
                        }
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findById(outsideTx.id!!)
                    }
                    found.shouldNotBeNull()
                    found.name shouldBe "outside-tx"
                }
            }

            context("partial failure scenarios") {

                it("rolls back a save-update sequence when it fails") {
                    val existing = tx.transactional {
                        testEntityRepository.save(TestEntity(name = "sequence-test", value = 1))
                    }

                    shouldThrow<RuntimeException> {
                        tx.transactional {
                            testEntityRepository.save(TestEntity(name = "new-entity", value = 2))

                            existing.value = 999
                            testEntityRepository.save(existing)

                            throw RuntimeException("sequence interrupted")
                        }
                    }

                    val afterCount = tx.readOnly { testEntityRepository.count() }
                    afterCount shouldBe 1

                    val found = tx.readOnly {
                        testEntityRepository.findById(existing.id!!)
                    }
                    found.shouldNotBeNull()
                    found.value shouldBe 1
                }
            }
        }
    }
}
