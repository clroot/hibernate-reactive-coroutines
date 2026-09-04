package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Complex query method tests.
 *
 * Verifies combinations of conditions and edge cases.
 */
@SpringBootTest(classes = [TestApplication::class])
class ComplexQueryMethodTest : IntegrationTestBase() {

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("complex query methods") {

            context("LIKE pattern edge cases") {

                it("matches all entities when Containing receives an empty string") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "empty-test-1", value = 1))
                        testEntityRepository.save(TestEntity(name = "empty-test-2", value = 2))
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findAllByNameContaining("")
                    }

                    found shouldHaveSize 2
                }

                it("matches Containing patterns with special characters") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "specialchar_underscore_test", value = 1))
                        testEntityRepository.save(TestEntity(name = "specialchar|pipe|test", value = 2))
                        testEntityRepository.save(TestEntity(name = "specialchar~tilde~test", value = 3))
                    }

                    val withUnderscore = tx.readOnly {
                        testEntityRepository.findAllByNameContaining("_underscore_")
                    }
                    val withPipe = tx.readOnly {
                        testEntityRepository.findAllByNameContaining("|pipe|")
                    }
                    val withTilde = tx.readOnly {
                        testEntityRepository.findAllByNameContaining("~tilde~")
                    }

                    withUnderscore shouldHaveSize 1
                    withPipe shouldHaveSize 1
                    withTilde shouldHaveSize 1
                }

                it("matches Containing patterns case-sensitively") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "CamelCase", value = 1))
                        testEntityRepository.save(TestEntity(name = "camelcase", value = 2))
                        testEntityRepository.save(TestEntity(name = "CAMELCASE", value = 3))
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findAllByNameContaining("Camel")
                    }

                    found shouldHaveSize 1
                    found[0].name shouldBe "CamelCase"
                }
            }

            context("comparison operator edge cases") {

                it("excludes the GreaterThan boundary") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "boundary-99", value = 99))
                        testEntityRepository.save(TestEntity(name = "boundary-100", value = 100))
                        testEntityRepository.save(TestEntity(name = "boundary-101", value = 101))
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findAllByValueGreaterThan(100)
                    }

                    val filtered = found.filter { it.name.startsWith("boundary-") }
                    filtered shouldHaveSize 1
                    filtered[0].value shouldBe 101
                }

                it("compares negative values") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "negative-1", value = -10))
                        testEntityRepository.save(TestEntity(name = "negative-2", value = -5))
                        testEntityRepository.save(TestEntity(name = "negative-3", value = 0))
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findAllByValueGreaterThan(-8)
                    }

                    val filtered = found.filter { it.name.startsWith("negative-") }
                    filtered shouldHaveSize 2
                    filtered.map { it.value }.toSet() shouldBe setOf(-5, 0)
                }
            }

            context("combined conditions (AND)") {

                it("finds an entity only when name and value both match") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "combo-match", value = 100))
                        testEntityRepository.save(TestEntity(name = "combo-match", value = 200))
                        testEntityRepository.save(TestEntity(name = "combo-other", value = 100))
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findByNameAndValue("combo-match", 100)
                    }

                    found.shouldNotBeNull()
                    found.name shouldBe "combo-match"
                    found.value shouldBe 100
                }

                it("returns null when only one condition matches") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "partial-match", value = 500))
                    }

                    val found1 = tx.readOnly {
                        testEntityRepository.findByNameAndValue("partial-match", 999)
                    }

                    val found2 = tx.readOnly {
                        testEntityRepository.findByNameAndValue("wrong-name", 500)
                    }

                    found1.shouldBeNull()
                    found2.shouldBeNull()
                }
            }

            context("combined sorting and conditions") {

                it("sorts the filtered results") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "zebra", value = 7777))
                        testEntityRepository.save(TestEntity(name = "apple", value = 7777))
                        testEntityRepository.save(TestEntity(name = "mango", value = 7777))
                        testEntityRepository.save(TestEntity(name = "banana", value = 8888))
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findAllByValueOrderByNameDesc(7777)
                    }

                    found shouldHaveSize 3
                    found.map { it.name } shouldBe listOf("zebra", "mango", "apple")
                }
            }

            context("existence checks") {

                it("returns true when multiple matching entities exist") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "duplicate-name", value = 1))
                        testEntityRepository.save(TestEntity(name = "duplicate-name", value = 2))
                        testEntityRepository.save(TestEntity(name = "duplicate-name", value = 3))
                    }

                    val exists = tx.readOnly {
                        testEntityRepository.existsByName("duplicate-name")
                    }

                    exists shouldBe true
                }
            }

            context("count") {

                it("returns zero when no entities match") {
                    val count = tx.readOnly {
                        testEntityRepository.countByValue(999999999)
                    }

                    count shouldBe 0
                }

                it("counts a large number of matching entities") {
                    tx.transactional {
                        repeat(50) { i ->
                            testEntityRepository.save(TestEntity(name = "mass-count-$i", value = 12345))
                        }
                    }

                    val count = tx.readOnly {
                        testEntityRepository.countByValue(12345)
                    }

                    count shouldBe 50
                }
            }

            context("delete") {

                it("completes without error when no entities match") {
                    tx.transactional {
                        testEntityRepository.deleteByName("non-existent-for-delete")
                    }
                }

                it("deletes all entities with the matching name") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "multi-delete", value = 1))
                        testEntityRepository.save(TestEntity(name = "multi-delete", value = 2))
                        testEntityRepository.save(TestEntity(name = "keep-this", value = 3))
                    }

                    tx.transactional {
                        testEntityRepository.deleteByName("multi-delete")
                    }

                    val deleted = tx.readOnly {
                        testEntityRepository.findAllByNameContaining("multi-delete")
                    }
                    val kept = tx.readOnly {
                        testEntityRepository.findByName("keep-this")
                    }

                    deleted.shouldBeEmpty()
                    kept.shouldNotBeNull()
                }
            }
        }
    }
}
