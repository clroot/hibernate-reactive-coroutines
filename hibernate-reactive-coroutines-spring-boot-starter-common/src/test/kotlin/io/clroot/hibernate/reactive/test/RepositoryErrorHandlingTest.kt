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
        describe("Repository 에러 핸들링") {

            context("트랜잭션 롤백") {

                it("save 후 예외 발생 시 트랜잭션이 롤백된다") {
                    var savedId: Long? = null

                    shouldThrow<RuntimeException> {
                        tx.transactional {
                            val entity = TestEntity(name = "rollback-test", value = 100)
                            val saved = testEntityRepository.save(entity)
                            savedId = saved.id
                            throw RuntimeException("의도적 롤백")
                        }
                    }

                    savedId.shouldNotBeNull()

                    // 롤백 확인
                    val found = tx.readOnly {
                        testEntityRepository.findById(savedId!!)
                    }
                    found.shouldBeNull()
                }

                it("여러 save 후 예외 발생 시 모든 작업이 롤백된다") {
                    shouldThrow<RuntimeException> {
                        tx.transactional {
                            testEntityRepository.save(TestEntity(name = "multi-rollback-1", value = 1))
                            testEntityRepository.save(TestEntity(name = "multi-rollback-2", value = 2))
                            testEntityRepository.save(TestEntity(name = "multi-rollback-3", value = 3))
                            throw RuntimeException("모든 작업 롤백")
                        }
                    }

                    val afterCount = tx.readOnly { testEntityRepository.count() }
                    afterCount shouldBe 0
                }

                it("delete 후 예외 발생 시 삭제가 롤백된다") {
                    // given
                    val saved = tx.transactional {
                        testEntityRepository.save(TestEntity(name = "delete-rollback", value = 999))
                    }

                    // when
                    shouldThrow<RuntimeException> {
                        tx.transactional {
                            testEntityRepository.delete(saved)
                            throw RuntimeException("삭제 롤백")
                        }
                    }

                    // then
                    val found = tx.readOnly {
                        testEntityRepository.findById(saved.id!!)
                    }
                    found.shouldNotBeNull()
                    found.name shouldBe "delete-rollback"
                }
            }

            context("예외 전파") {

                it("Repository 내부 예외가 호출자에게 전파된다") {
                    val exception = shouldThrow<IllegalStateException> {
                        tx.transactional {
                            testEntityRepository.save(TestEntity(name = "exception-test", value = 1))
                            throw IllegalStateException("커스텀 예외")
                        }
                    }

                    exception.message shouldBe "커스텀 예외"
                }
            }

            context("트랜잭션 경계") {

                it("트랜잭션 외부에서 수행된 변경은 롤백에 영향받지 않는다") {
                    // 트랜잭션 외부에서 먼저 저장
                    val outsideTx = tx.transactional {
                        testEntityRepository.save(TestEntity(name = "outside-tx", value = 100))
                    }

                    // 새 트랜잭션에서 예외 발생
                    shouldThrow<RuntimeException> {
                        tx.transactional {
                            testEntityRepository.save(TestEntity(name = "inside-tx", value = 200))
                            throw RuntimeException("롤백")
                        }
                    }

                    // 이전 트랜잭션의 데이터는 유지됨
                    val found = tx.readOnly {
                        testEntityRepository.findById(outsideTx.id!!)
                    }
                    found.shouldNotBeNull()
                    found.name shouldBe "outside-tx"
                }
            }

            context("부분 실패 시나리오") {

                it("save-update-delete 시퀀스에서 중간 실패 시 전체 롤백") {
                    // given
                    val existing = tx.transactional {
                        testEntityRepository.save(TestEntity(name = "sequence-test", value = 1))
                    }

                    // when
                    shouldThrow<RuntimeException> {
                        tx.transactional {
                            // 1. 새 엔티티 저장
                            testEntityRepository.save(TestEntity(name = "new-entity", value = 2))

                            // 2. 기존 엔티티 업데이트
                            existing.value = 999
                            testEntityRepository.save(existing)

                            // 3. 예외 발생
                            throw RuntimeException("시퀀스 중단")
                        }
                    }

                    // then - 기존 1개만 존재해야 함
                    val afterCount = tx.readOnly { testEntityRepository.count() }
                    afterCount shouldBe 1

                    val found = tx.readOnly {
                        testEntityRepository.findById(existing.id!!)
                    }
                    found.shouldNotBeNull()
                    found.value shouldBe 1 // 원래 값 유지
                }
            }
        }
    }
}
