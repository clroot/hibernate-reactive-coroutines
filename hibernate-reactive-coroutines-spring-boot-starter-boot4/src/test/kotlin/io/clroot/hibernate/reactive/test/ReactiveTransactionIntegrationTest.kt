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
                it("엔티티를 저장할 수 있다") {
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
                it("저장된 엔티티를 조회할 수 있다") {
                    // given
                    val entity = TestEntity(name = "findMe", value = 200)
                    val saved = sessions.write { session ->
                        session.persist(entity).replaceWith(entity)
                    }

                    // when
                    val found = sessions.read { session ->
                        session.find(TestEntity::class.java, saved.id)
                    }

                    // then
                    found.shouldNotBeNull()
                    found.name shouldBe "findMe"
                    found.value shouldBe 200
                }

                it("존재하지 않는 엔티티는 null을 반환한다") {
                    val found = sessions.read { session ->
                        session.find(TestEntity::class.java, 99999L)
                    }

                    found.shouldBeNull()
                }
            }
        }

        describe("ReactiveTransactionExecutor") {
            context("transactional") {
                it("여러 write 작업이 원자적으로 수행된다") {
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

                it("예외 발생 시 모든 변경이 롤백된다") {
                    var savedId: Long? = null

                    shouldThrow<RuntimeException> {
                        tx.transactional {
                            val entity = TestEntity(name = "rollback", value = 999)
                            val saved = sessions.write { session ->
                                session.persist(entity).replaceWith(entity)
                            }
                            savedId = saved.id

                            throw RuntimeException("의도적 롤백")
                        }
                    }

                    savedId.shouldNotBeNull()

                    // 롤백 확인 - 새 세션에서 조회
                    val found = sessions.read { session ->
                        session.find(TestEntity::class.java, savedId)
                    }
                    found.shouldBeNull()
                }

                it("중첩된 transactional 블록에서 세션이 재사용된다") {
                    val result = tx.transactional {
                        val outer = TestEntity(name = "outer", value = 1)
                        sessions.write { session ->
                            session.persist(outer).replaceWith(outer)
                        }

                        // 중첩 transactional
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
            }

            context("readOnly") {
                it("read 작업은 정상 수행된다") {
                    // given
                    val entity = TestEntity(name = "readOnly", value = 500)
                    val saved = sessions.write { session ->
                        session.persist(entity).replaceWith(entity)
                    }

                    // when
                    val found = tx.readOnly {
                        sessions.read { session ->
                            session.find(TestEntity::class.java, saved.id)
                        }
                    }

                    // then
                    found.shouldNotBeNull()
                    found.name shouldBe "readOnly"
                }

                it("write 시도 시 ReadOnlyTransactionException이 발생한다") {
                    shouldThrow<ReadOnlyTransactionException> {
                        tx.readOnly {
                            sessions.write { session ->
                                val entity = TestEntity(name = "forbidden", value = 0)
                                session.persist(entity).replaceWith(entity)
                            }
                        }
                    }
                }
            }
        }
    }
}
