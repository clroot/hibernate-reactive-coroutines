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
 * Verifies deletion semantics and result-size limits.
 *
 * Verifies against a real database that deletion loads and removes entities instead of issuing a
 * bulk `DELETE`, which bypasses cascades and the persistence context.
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

            it("cascades deletion to children") {
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

            it("silently ignores a nonexistent ID") {
                tx.transactional {
                    testEntityRepository.deleteById(-1L)
                }
            }

            it("does not find an entity deleted in the same transaction") {
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

            it("cascades deletion to children") {
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

        describe("derived deleteBy method") {

            it("returns the number of deleted entities") {
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

        describe("Top/First result limits") {

            it("findTop2By returns only the first two results") {
                tx.transactional {
                    testEntityRepository.save(TestEntity(name = "low", value = 1))
                    testEntityRepository.save(TestEntity(name = "mid", value = 5))
                    testEntityRepository.save(TestEntity(name = "high", value = 9))
                }

                val top = tx.readOnly { testEntityRepository.findTop2ByOrderByValueDesc() }

                top.map { it.name } shouldContainExactly listOf("high", "mid")
            }

            it("findFirstBy returns the first result without an error when multiple entities match") {
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
