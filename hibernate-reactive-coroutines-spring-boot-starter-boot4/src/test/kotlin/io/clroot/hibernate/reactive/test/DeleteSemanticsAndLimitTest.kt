package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.test.entity.ChildEntity
import io.clroot.hibernate.reactive.test.entity.ParentEntity
import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.ChildEntityRepository
import io.clroot.hibernate.reactive.test.repository.ParentEntityRepository
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * 삭제 의미론과 결과 개수 제한 동작을 검증합니다.
 *
 * bulk `DELETE` 문은 cascade와 영속성 컨텍스트를 건너뛰므로, 로드 후 제거 방식으로
 * 동작하는지 실제 DB에 대해 확인합니다.
 */
@SpringBootTest
class DeleteSemanticsAndLimitTest : IntegrationTestBase() {

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var parentEntityRepository: ParentEntityRepository

    @Autowired
    private lateinit var childEntityRepository: ChildEntityRepository

    init {
        describe("deleteById") {

            it("자식까지 cascade 삭제한다") {
                val parentId = tx.transactional {
                    val parent = ParentEntity(name = "parent")
                    parent.addChild(ChildEntity(name = "child-1"))
                    parent.addChild(ChildEntity(name = "child-2"))
                    parentEntityRepository.save(parent).id!!
                }

                tx.transactional {
                    parentEntityRepository.deleteById(parentId)
                }

                tx.readOnly {
                    parentEntityRepository.findById(parentId) shouldBe null
                }
                countChildRows() shouldBe 0L
            }

            it("존재하지 않는 ID는 조용히 무시한다") {
                tx.transactional {
                    testEntityRepository.deleteById(-1L)
                }
            }

            it("같은 트랜잭션 안에서 삭제한 엔티티는 다시 조회되지 않는다") {
                val id = tx.transactional {
                    testEntityRepository.save(TestEntity(name = "gone", value = 1)).id!!
                }

                tx.transactional {
                    testEntityRepository.deleteById(id)
                    testEntityRepository.findById(id) shouldBe null
                }
            }
        }

        describe("deleteAll") {

            it("자식까지 cascade 삭제한다") {
                tx.transactional {
                    val parent = ParentEntity(name = "parent")
                    parent.addChild(ChildEntity(name = "child-1"))
                    parentEntityRepository.save(parent)
                }

                tx.transactional {
                    parentEntityRepository.deleteAll()
                }

                tx.readOnly {
                    parentEntityRepository.count() shouldBe 0L
                }
                countChildRows() shouldBe 0L
            }
        }

        describe("파생 deleteBy 메서드") {

            it("삭제 건수를 반환한다") {
                tx.transactional {
                    testEntityRepository.save(TestEntity(name = "a", value = 7))
                    testEntityRepository.save(TestEntity(name = "b", value = 7))
                    testEntityRepository.save(TestEntity(name = "c", value = 8))
                }

                val deleted = tx.transactional {
                    testEntityRepository.deleteAllByValue(7)
                }

                deleted shouldBe 2L
                tx.readOnly { testEntityRepository.count() } shouldBe 1L
            }
        }

        describe("Top/First 개수 제한") {

            it("findTop2By는 상위 2건만 반환한다") {
                tx.transactional {
                    testEntityRepository.save(TestEntity(name = "low", value = 1))
                    testEntityRepository.save(TestEntity(name = "mid", value = 5))
                    testEntityRepository.save(TestEntity(name = "high", value = 9))
                }

                val top = tx.readOnly { testEntityRepository.findTop2ByOrderByValueDesc() }

                top.map { it.name } shouldContainExactly listOf("high", "mid")
            }

            it("findFirstBy는 여러 건이 매칭돼도 예외 없이 첫 건을 반환한다") {
                tx.transactional {
                    testEntityRepository.save(TestEntity(name = "low", value = 1))
                    testEntityRepository.save(TestEntity(name = "high", value = 9))
                }

                val first = tx.readOnly { testEntityRepository.findFirstByOrderByValueDesc() }

                first.shouldNotBeNull().name shouldBe "high"
            }
        }
    }

    private suspend fun countChildRows(): Long = tx.readOnly { childEntityRepository.count() }
}
