package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.clroot.hibernate.reactive.ReactiveSessionProvider
import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.ReadOnlyTransactionException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.hibernate.FlushMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(classes = [TestApplication::class])
class ReactiveTransactionIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    @Autowired
    private lateinit var sessions: ReactiveSessionProvider

    init {
        describe("ReactiveSessionProvider") {
            context("write") {
                it("saves an entity") {
                    val entity = TestEntity(name = "test", value = 100)

                    val saved = sessions.write { session ->
                        session.persist(entity).replaceWith(entity)
                    }

                    saved.id.shouldNotBeNull()
                    saved.name shouldBe "test"
                    saved.value shouldBe 100
                }
            }

            context("read") {
                it("finds a saved entity") {
                    val entity = TestEntity(name = "findMe", value = 200)
                    val saved = sessions.write { session ->
                        session.persist(entity).replaceWith(entity)
                    }

                    val found = sessions.read { session ->
                        session.find(TestEntity::class.java, saved.id)
                    }

                    found.shouldNotBeNull()
                    found.name shouldBe "findMe"
                    found.value shouldBe 200
                }

                it("returns null for a nonexistent entity") {
                    val found = sessions.read { session ->
                        session.find(TestEntity::class.java, 99999L)
                    }

                    found.shouldBeNull()
                }
            }
        }

        describe("ReactiveTransactionExecutor") {
            context("transactional") {
                it("performs multiple write operations atomically") {
                    val result = tx.transactional {
                        val entity1 = TestEntity(name = "atomic1", value = 1)
                        val entity2 = TestEntity(name = "atomic2", value = 2)

                        sessions.write { session ->
                            session.persist(entity1).replaceWith(entity1)
                        }
                        sessions.write { session ->
                            session.persist(entity2).replaceWith(entity2)
                        }

                        "success"
                    }

                    result shouldBe "success"
                }

                it("rolls back all changes when an exception occurs") {
                    var savedId: Long? = null

                    shouldThrow<RuntimeException> {
                        tx.transactional {
                            val entity = TestEntity(name = "rollback", value = 999)
                            val saved = sessions.write { session ->
                                session.persist(entity).replaceWith(entity)
                            }
                            savedId = saved.id

                            throw RuntimeException("intentional rollback")
                        }
                    }

                    savedId.shouldNotBeNull()

                    val found = sessions.read { session ->
                        session.find(TestEntity::class.java, savedId)
                    }
                    found.shouldBeNull()
                }

                it("reuses the session in nested transactional blocks") {
                    val result = tx.transactional {
                        val outer = TestEntity(name = "outer", value = 1)
                        sessions.write { session ->
                            session.persist(outer).replaceWith(outer)
                        }

                        tx.transactional {
                            val inner = TestEntity(name = "inner", value = 2)
                            sessions.write { session ->
                                session.persist(inner).replaceWith(inner)
                            }
                        }

                        "nested success"
                    }

                    result shouldBe "nested success"
                }

                it("propagates the caller coroutine context into the transaction block") {
                    val traceContext = ThreadLocal<String>()
                    withContext(CoroutineName("request-trace") + traceContext.asContextElement("trace-123")) {
                        tx.transactional {
                            currentCoroutineContext()[CoroutineName]?.name shouldBe "request-trace"
                            traceContext.get() shouldBe "trace-123"
                        }
                    }
                }
            }

            context("readOnly") {
                it("performs read operations") {
                    val entity = TestEntity(name = "readOnly", value = 500)
                    val saved = sessions.write { session ->
                        session.persist(entity).replaceWith(entity)
                    }

                    val found = tx.readOnly {
                        sessions.read { session ->
                            session.find(TestEntity::class.java, saved.id)
                        }
                    }

                    found.shouldNotBeNull()
                    found.name shouldBe "readOnly"
                }

                it("throws ReadOnlyTransactionException when a write is attempted") {
                    shouldThrow<ReadOnlyTransactionException> {
                        tx.readOnly {
                            sessions.write { session ->
                                val entity = TestEntity(name = "forbidden", value = 0)
                                session.persist(entity).replaceWith(entity)
                            }
                        }
                    }
                }

                it("disables dirty checking and auto-flush") {
                    val entity = TestEntity(name = "readOnlyDirtyChecking", value = 100)
                    val saved = tx.transactional {
                        sessions.write { session ->
                            session.persist(entity).replaceWith(entity)
                        }
                    }

                    tx.readOnly {
                        sessions.read { session ->
                            session.isDefaultReadOnly shouldBe true
                            session.flushMode shouldBe FlushMode.MANUAL

                            session.find(TestEntity::class.java, saved.id)
                                .invoke { found -> found.value = 999 }
                                .chain { _: TestEntity ->
                                    session.createQuery("SELECT e FROM TestEntity e", TestEntity::class.java)
                                        .resultList
                                }
                                .replaceWith(Unit)
                        }
                    }

                    val found = sessions.read { session ->
                        session.find(TestEntity::class.java, saved.id)
                    }
                    found.shouldNotBeNull()
                    found.value shouldBe 100
                }
            }
        }
    }
}
