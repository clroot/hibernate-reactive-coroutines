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
 * 트랜잭션 에러 시나리오 테스트.
 *
 * 프로덕션에서 발생할 수 있는 다양한 에러 상황을 검증합니다.
 */
@SpringBootTest(classes = [TestApplication::class])
class TransactionErrorScenariosTest : IntegrationTestBase() {

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("트랜잭션 에러 시나리오") {

            context("타임아웃") {

                it("트랜잭션 타임아웃 초과 시 TimeoutCancellationException이 발생한다") {
                    shouldThrow<TimeoutCancellationException> {
                        tx.transactional(timeout = 100.milliseconds) {
                            testEntityRepository.save(TestEntity(name = "timeout-test", value = 1))
                            // 타임아웃보다 긴 대기
                            kotlinx.coroutines.delay(500)
                            testEntityRepository.save(TestEntity(name = "should-not-save", value = 2))
                        }
                    }
                }

                it("readOnly 타임아웃 초과 시 TimeoutCancellationException이 발생한다") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "read-timeout-test", value = 1))
                    }

                    // when & then
                    shouldThrow<TimeoutCancellationException> {
                        tx.readOnly(timeout = 100.milliseconds) {
                            testEntityRepository.findAll()
                            kotlinx.coroutines.delay(500)
                            testEntityRepository.count()
                        }
                    }
                }

                it("타임아웃 발생 시 트랜잭션이 롤백된다") {
                    var savedId: Long? = null

                    shouldThrow<TimeoutCancellationException> {
                        tx.transactional(timeout = 100.milliseconds) {
                            val saved = testEntityRepository.save(TestEntity(name = "timeout-rollback", value = 1))
                            savedId = saved.id
                            kotlinx.coroutines.delay(500)
                        }
                    }

                    // 롤백 확인 - 저장이 안 되어야 함
                    val found = tx.readOnly {
                        savedId?.let { testEntityRepository.findById(it) }
                    }
                    found.shouldBeNull()
                }
            }

            context("중첩 트랜잭션 에러") {

                it("내부 트랜잭션 예외가 외부로 전파되어 전체 롤백된다") {
                    var outerEntityId: Long? = null

                    shouldThrow<RuntimeException> {
                        tx.transactional {
                            val outer = testEntityRepository.save(TestEntity(name = "outer-entity", value = 1))
                            outerEntityId = outer.id

                            // 중첩 트랜잭션 (REQUIRED 동작으로 같은 트랜잭션 사용)
                            tx.transactional {
                                testEntityRepository.save(TestEntity(name = "inner-entity", value = 2))
                                throw RuntimeException("내부 트랜잭션 실패")
                            }
                        }
                    }

                    // 외부 엔티티도 롤백 확인
                    val found = tx.readOnly {
                        outerEntityId?.let { testEntityRepository.findById(it) }
                    }
                    found.shouldBeNull()

                    val count = tx.readOnly { testEntityRepository.count() }
                    count shouldBe 0
                }

                it("중첩 readOnly 내에서 예외 발생 시 전체 롤백") {
                    var savedId: Long? = null

                    shouldThrow<RuntimeException> {
                        tx.transactional {
                            val saved = testEntityRepository.save(TestEntity(name = "nested-readonly-test", value = 1))
                            savedId = saved.id

                            tx.readOnly {
                                testEntityRepository.findById(saved.id!!)
                                throw RuntimeException("readOnly 내부 실패")
                            }
                        }
                    }

                    val found = tx.readOnly {
                        savedId?.let { testEntityRepository.findById(it) }
                    }
                    found.shouldBeNull()
                }
            }

            context("ReadOnly 위반") {

                it("readOnly 블록 내에서 save 시도 시 ReadOnlyTransactionException 발생") {
                    shouldThrow<ReadOnlyTransactionException> {
                        tx.readOnly {
                            testEntityRepository.save(TestEntity(name = "readonly-violation", value = 1))
                        }
                    }
                }

                it("readOnly 블록 내에서 delete 시도 시 ReadOnlyTransactionException 발생") {
                    // given
                    val saved = tx.transactional {
                        testEntityRepository.save(TestEntity(name = "delete-readonly-test", value = 1))
                    }

                    // when & then
                    shouldThrow<ReadOnlyTransactionException> {
                        tx.readOnly {
                            testEntityRepository.delete(saved)
                        }
                    }

                    // 삭제 안 됨 확인
                    val found = tx.readOnly {
                        testEntityRepository.findById(saved.id!!)
                    }
                    found.shouldNotBeNull()
                }

                it("중첩된 transactional 내 readOnly는 부모의 READ_WRITE 컨텍스트를 재사용한다") {
                    // 현재 구현: readOnly는 기존 컨텍스트가 있으면 재사용 (REQUIRED 동작)
                    // 따라서 transactional 내부의 readOnly에서 쓰기가 가능함

                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "outer-write", value = 1))

                        tx.readOnly {
                            // 부모 컨텍스트가 READ_WRITE이므로 쓰기 가능
                            testEntityRepository.save(TestEntity(name = "inner-write", value = 2))
                        }
                    }

                    // 둘 다 저장됨
                    val count = tx.readOnly { testEntityRepository.count() }
                    count shouldBe 2
                }
            }

            context("복합 실패 시나리오") {

                it("여러 엔티티 저장 중 마지막에서 실패하면 모두 롤백") {
                    shouldThrow<RuntimeException> {
                        tx.transactional {
                            repeat(10) { i ->
                                testEntityRepository.save(TestEntity(name = "batch-$i", value = i))
                            }
                            throw RuntimeException("마지막에 실패")
                        }
                    }

                    val count = tx.readOnly { testEntityRepository.count() }
                    count shouldBe 0
                }

                it("저장-조회-수정-삭제 복합 시퀀스에서 실패 시 전체 롤백") {
                    // given
                    val existing = tx.transactional {
                        testEntityRepository.save(TestEntity(name = "complex-sequence", value = 100))
                    }

                    // when
                    shouldThrow<RuntimeException> {
                        tx.transactional {
                            // 1. 새 엔티티 저장
                            val newEntity = testEntityRepository.save(TestEntity(name = "new-in-sequence", value = 1))

                            // 2. 기존 엔티티 조회
                            val found = testEntityRepository.findById(existing.id!!)

                            // 3. 수정
                            found!!.value = 999
                            testEntityRepository.save(found)

                            // 4. 새 엔티티 삭제
                            testEntityRepository.delete(newEntity)

                            // 5. 실패
                            throw RuntimeException("복합 시퀀스 실패")
                        }
                    }

                    // then - 원래 상태 유지
                    val afterCount = tx.readOnly { testEntityRepository.count() }
                    afterCount shouldBe 1

                    val afterExisting = tx.readOnly { testEntityRepository.findById(existing.id!!) }
                    afterExisting.shouldNotBeNull()
                    afterExisting.value shouldBe 100 // 원래 값 유지
                }
            }

            context("예외 타입별 처리") {

                it("RuntimeException은 롤백된다") {
                    shouldThrow<RuntimeException> {
                        tx.transactional {
                            testEntityRepository.save(TestEntity(name = "runtime-ex", value = 1))
                            throw RuntimeException("런타임 예외")
                        }
                    }

                    val count = tx.readOnly { testEntityRepository.count() }
                    count shouldBe 0
                }

                it("IllegalStateException은 롤백된다") {
                    shouldThrow<IllegalStateException> {
                        tx.transactional {
                            testEntityRepository.save(TestEntity(name = "illegal-state", value = 1))
                            throw IllegalStateException("잘못된 상태")
                        }
                    }

                    val count = tx.readOnly { testEntityRepository.count() }
                    count shouldBe 0
                }

                it("IllegalArgumentException은 롤백된다") {
                    shouldThrow<IllegalArgumentException> {
                        tx.transactional {
                            testEntityRepository.save(TestEntity(name = "illegal-arg", value = 1))
                            throw IllegalArgumentException("잘못된 인자")
                        }
                    }

                    val count = tx.readOnly { testEntityRepository.count() }
                    count shouldBe 0
                }
            }
        }
    }
}
