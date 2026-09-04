package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.currentContextOrNull
import io.clroot.hibernate.reactive.currentSessionOrNull
import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(classes = [TestApplication::class])
class SessionLifecycleTest : IntegrationTestBase() {
    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("session lifecycle") {

            context("session context availability") {

                it("has no session context outside a transaction") {
                    val context = currentContextOrNull()
                    context.shouldBeNull()
                }

                it("has no session outside a transaction") {
                    val session = currentSessionOrNull()
                    session.shouldBeNull()
                }

                it("has a session context within a transactional block") {
                    tx.transactional {
                        val context = currentContextOrNull()
                        context.shouldNotBeNull()
                        context.isReadOnly.shouldBeFalse()
                    }
                }

                it("has a read-only session context within a readOnly block") {
                    tx.readOnly {
                        val context = currentContextOrNull()
                        context.shouldNotBeNull()
                        context.isReadOnly.shouldBeTrue()
                    }
                }

                it("clears the session context after a transactional block") {
                    tx.transactional {
                        currentContextOrNull().shouldNotBeNull()
                    }

                    currentContextOrNull().shouldBeNull()
                }
            }

            context("session reuse") {

                it("uses the same session for nested transactional blocks") {
                    tx.transactional {
                        val outerSession = currentSessionOrNull()
                        outerSession.shouldNotBeNull()

                        tx.transactional {
                            val innerSession = currentSessionOrNull()
                            innerSession.shouldNotBeNull()
                            (innerSession === outerSession).shouldBeTrue()
                        }
                    }
                }

                it("uses the same session for nested readOnly blocks") {
                    tx.readOnly {
                        val outerSession = currentSessionOrNull()
                        outerSession.shouldNotBeNull()

                        tx.readOnly {
                            val innerSession = currentSessionOrNull()
                            innerSession.shouldNotBeNull()
                            (innerSession === outerSession).shouldBeTrue()
                        }
                    }
                }

                it("uses the same session for readOnly within transactional") {
                    tx.transactional {
                        val outerSession = currentSessionOrNull()

                        tx.readOnly {
                            val innerSession = currentSessionOrNull()
                            (innerSession === outerSession).shouldBeTrue()
                        }
                    }
                }
            }

            context("session state and mode") {

                it("uses READ_WRITE mode for transactional") {
                    tx.transactional {
                        val context = currentContextOrNull()!!
                        context.isReadOnly.shouldBeFalse()
                        context.mode shouldBe io.clroot.hibernate.reactive.TransactionMode.READ_WRITE
                    }
                }

                it("uses READ_ONLY mode for readOnly") {
                    tx.readOnly {
                        val context = currentContextOrNull()!!
                        context.isReadOnly.shouldBeTrue()
                        context.mode shouldBe io.clroot.hibernate.reactive.TransactionMode.READ_ONLY
                    }
                }

                it("retains READ_WRITE mode for readOnly within READ_WRITE") {
                    tx.transactional {
                        currentContextOrNull()!!.isReadOnly.shouldBeFalse()

                        tx.readOnly {
                            // Nested blocks reuse the parent context and cannot change its mode.
                            currentContextOrNull()!!.isReadOnly.shouldBeFalse()
                        }
                    }
                }
            }

            context("consecutive transactions") {

                it("uses separate sessions for consecutive transactions") {
                    var firstSessionHash: Int? = null
                    var secondSessionHash: Int? = null

                    tx.transactional {
                        firstSessionHash = currentSessionOrNull().hashCode()
                        testEntityRepository.save(TestEntity(name = "first-tx", value = 1))
                    }

                    tx.transactional {
                        secondSessionHash = currentSessionOrNull().hashCode()
                        testEntityRepository.save(TestEntity(name = "second-tx", value = 2))
                    }

                    firstSessionHash.shouldNotBeNull()
                    secondSessionHash.shouldNotBeNull()

                    val count = tx.readOnly { testEntityRepository.count() }
                    count shouldBe 2
                }

                it("does not let a failed transaction affect the next transaction") {
                    try {
                        tx.transactional {
                            testEntityRepository.save(TestEntity(name = "fail-tx", value = 1))
                            throw RuntimeException("intentional failure")
                        }
                    } catch (e: RuntimeException) {
                    }

                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "success-tx", value = 2))
                    }

                    val count = tx.readOnly { testEntityRepository.count() }
                    count shouldBe 1

                    val found = tx.readOnly { testEntityRepository.findByName("success-tx") }
                    found.shouldNotBeNull()
                }
            }

            context("timeouts and sessions") {

                it("cleans up the session after a successful transaction with a timeout") {
                    tx.transactional(timeout = kotlin.time.Duration.parse("5s")) {
                        currentContextOrNull().shouldNotBeNull()
                        testEntityRepository.save(TestEntity(name = "timeout-session", value = 1))
                    }

                    currentContextOrNull().shouldBeNull()

                    val found = tx.readOnly { testEntityRepository.findByName("timeout-session") }
                    found.shouldNotBeNull()
                }
            }
        }
    }
}
