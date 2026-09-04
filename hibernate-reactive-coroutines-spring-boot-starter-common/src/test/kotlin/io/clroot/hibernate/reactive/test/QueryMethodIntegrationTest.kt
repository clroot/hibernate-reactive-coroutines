package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Integration tests for automatic query method generation.
 *
 * Verifies Spring Data-style query generation from method names.
 */
@SpringBootTest(classes = [TestApplication::class])
class QueryMethodIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("Query methods") {

            context("findByName - single result") {
                it("finds an entity by name") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "uniqueName123", value = 100))
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findByName("uniqueName123")
                    }

                    found.shouldNotBeNull()
                    found.name shouldBe "uniqueName123"
                    found.value shouldBe 100
                }

                it("returns null for a nonexistent name") {
                    val found = tx.readOnly {
                        testEntityRepository.findByName("nonExistentName999")
                    }

                    found.shouldBeNull()
                }
            }

            context("findAllByValue - list result") {
                it("finds multiple entities by value") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "listTest1", value = 777))
                        testEntityRepository.save(TestEntity(name = "listTest2", value = 777))
                        testEntityRepository.save(TestEntity(name = "listTest3", value = 888))
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findAllByValue(777)
                    }

                    found shouldHaveSize 2
                    found.map { it.name }.toSet() shouldBe setOf("listTest1", "listTest2")
                }

                it("returns an empty list when no value matches") {
                    val found = tx.readOnly {
                        testEntityRepository.findAllByValue(999999)
                    }

                    found.shouldBeEmpty()
                }
            }

            context("In/NotIn collections") {
                it("returns no results for empty IN and all results for empty NOT IN") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "collection-a", value = 1))
                        testEntityRepository.save(TestEntity(name = "collection-b", value = 2))
                    }

                    tx.readOnly {
                        testEntityRepository.findAllByNameIn(emptyList()).shouldBeEmpty()
                        testEntityRepository.findAllByNameNotIn(emptyList()).map { it.name }.toSet() shouldBe
                                setOf("collection-a", "collection-b")
                    }
                }

                it("binds a nonempty collection") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "collection-in-a", value = 1))
                        testEntityRepository.save(TestEntity(name = "collection-in-b", value = 2))
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findAllByNameIn(listOf("collection-in-b"))
                    }
                    found.map { it.name } shouldBe listOf("collection-in-b")
                }
            }

            context("existsByName - existence check") {
                it("returns true for an existing name") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "existsTest", value = 1))
                    }

                    val exists = tx.readOnly {
                        testEntityRepository.existsByName("existsTest")
                    }

                    exists shouldBe true
                }

                it("returns false for a nonexistent name") {
                    val exists = tx.readOnly {
                        testEntityRepository.existsByName("doesNotExist999")
                    }

                    exists shouldBe false
                }
            }

            context("countByValue - count") {
                it("counts entities by value") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "countTest1", value = 555))
                        testEntityRepository.save(TestEntity(name = "countTest2", value = 555))
                        testEntityRepository.save(TestEntity(name = "countTest3", value = 555))
                    }

                    val count = tx.readOnly {
                        testEntityRepository.countByValue(555)
                    }

                    count shouldBe 3
                }

                it("returns zero when no value matches") {
                    val count = tx.readOnly {
                        testEntityRepository.countByValue(999888)
                    }

                    count shouldBe 0
                }
            }

            context("deleteByName - deletion") {
                it("deletes an entity by name") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "deleteTest", value = 1))
                    }

                    val beforeDelete = tx.readOnly {
                        testEntityRepository.findByName("deleteTest")
                    }
                    beforeDelete.shouldNotBeNull()

                    tx.transactional {
                        testEntityRepository.deleteByName("deleteTest")
                    }

                    val afterDelete = tx.readOnly {
                        testEntityRepository.findByName("deleteTest")
                    }
                    afterDelete.shouldBeNull()
                }
            }

            context("findAllByNameContaining - LIKE search") {
                it("finds entities whose names contain a string") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "apple_fruit", value = 1))
                        testEntityRepository.save(TestEntity(name = "pineapple_fruit", value = 2))
                        testEntityRepository.save(TestEntity(name = "banana_fruit", value = 3))
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findAllByNameContaining("apple")
                    }

                    found shouldHaveSize 2
                    found.map { it.name }.toSet() shouldBe setOf("apple_fruit", "pineapple_fruit")
                }
            }

            context("findByNameAndValue - combined criteria") {
                it("finds an entity when both name and value match") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "combo", value = 111))
                        testEntityRepository.save(TestEntity(name = "combo", value = 222))
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findByNameAndValue("combo", 111)
                    }

                    found.shouldNotBeNull()
                    found.name shouldBe "combo"
                    found.value shouldBe 111
                }

                it("returns null when only the name matches") {
                    val found = tx.readOnly {
                        testEntityRepository.findByNameAndValue("combo", 999)
                    }

                    found.shouldBeNull()
                }
            }

            context("findAllByValueGreaterThan - comparison") {
                it("finds entities whose values exceed the threshold") {
                    // This range avoids values created by other test classes.
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "gt1", value = 25000))
                        testEntityRepository.save(TestEntity(name = "gt2", value = 26000))
                        testEntityRepository.save(TestEntity(name = "gt3", value = 27000))
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findAllByValueGreaterThan(25500)
                    }

                    // Filter out higher values inserted by other test classes.
                    found.filter { it.value in 25501..28000 } shouldHaveSize 2
                    found.filter { it.value in 25501..28000 }.all { it.value > 25500 } shouldBe true
                }
            }

            context("findAllByValueOrderByNameDesc - sorting") {
                it("finds by value and sorts names in descending order") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "aaa_sort", value = 4444))
                        testEntityRepository.save(TestEntity(name = "ccc_sort", value = 4444))
                        testEntityRepository.save(TestEntity(name = "bbb_sort", value = 4444))
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findAllByValueOrderByNameDesc(4444)
                    }

                    found shouldHaveSize 3
                    found.map { it.name } shouldContainExactly listOf("ccc_sort", "bbb_sort", "aaa_sort")
                }
            }
        }
    }
}
