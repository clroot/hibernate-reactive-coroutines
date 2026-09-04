package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort

/**
 * Advanced `@Query` annotation tests.
 *
 * Verifies named and positional parameters, `@Modifying`, and pagination.
 */
@SpringBootTest(classes = [TestApplication::class])
class AdvancedQueryAnnotationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("advanced @Query features") {

            context("Named Parameter (@Param)") {

                it("finds entities with a single named parameter") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "named-param-1", value = 1001))
                        testEntityRepository.save(TestEntity(name = "named-param-2", value = 1001))
                        testEntityRepository.save(TestEntity(name = "named-param-3", value = 1002))
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findByValueWithQuery(1001)
                    }

                    found shouldHaveSize 2
                    found.map { it.name } shouldContainExactlyInAnyOrder listOf("named-param-1", "named-param-2")
                }

                it("finds an entity with multiple named parameters") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "multi-param", value = 2001))
                        testEntityRepository.save(TestEntity(name = "multi-param", value = 2002))
                        testEntityRepository.save(TestEntity(name = "other-name", value = 2001))
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findByNameAndValueWithQuery("multi-param", 2001)
                    }

                    found.shouldNotBeNull()
                    found.name shouldBe "multi-param"
                    found.value shouldBe 2001
                }

                it("returns no result when named parameters do not match") {
                    val found = tx.readOnly {
                        testEntityRepository.findByNameAndValueWithQuery("non-existent", 9999)
                    }

                    found.shouldBeNull()
                }
            }

            context("Positional Parameter") {

                it("finds values strictly within a range") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "range-1", value = 10))
                        testEntityRepository.save(TestEntity(name = "range-2", value = 20))
                        testEntityRepository.save(TestEntity(name = "range-3", value = 30))
                        testEntityRepository.save(TestEntity(name = "range-4", value = 40))
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findByValueBetweenWithQuery(15, 35)
                    }

                    found shouldHaveSize 2
                    found.map { it.value } shouldContainExactlyInAnyOrder listOf(20, 30)
                }

                it("excludes range boundaries") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "boundary-pos-10", value = 10))
                        testEntityRepository.save(TestEntity(name = "boundary-pos-20", value = 20))
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findByValueBetweenWithQuery(10, 20)
                    }

                    found.shouldBeEmpty()
                }
            }

            context("@Modifying UPDATE") {

                it("updates matching values in bulk") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "update-target-1", value = 3000))
                        testEntityRepository.save(TestEntity(name = "update-target-2", value = 3000))
                        testEntityRepository.save(TestEntity(name = "update-target-3", value = 3001))
                    }

                    val updatedCount = tx.transactional {
                        testEntityRepository.updateValue(3000, 3999)
                    }

                    updatedCount shouldBe 2

                    val updated = tx.readOnly {
                        testEntityRepository.findByValueWithQuery(3999)
                    }
                    updated shouldHaveSize 2

                    val unchanged = tx.readOnly {
                        testEntityRepository.findByValueWithQuery(3001)
                    }
                    unchanged shouldHaveSize 1
                }

                it("returns zero when no values are updated") {
                    val updatedCount = tx.transactional {
                        testEntityRepository.updateValue(999999, 888888)
                    }

                    updatedCount shouldBe 0
                }
            }

            context("@Modifying DELETE") {

                it("deletes entities matching the condition") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "delete-query-1", value = 4000))
                        testEntityRepository.save(TestEntity(name = "delete-query-2", value = 4000))
                        testEntityRepository.save(TestEntity(name = "keep-query-1", value = 4001))
                    }

                    val deletedCount = tx.transactional {
                        testEntityRepository.deleteByValueWithQuery(4000)
                    }

                    deletedCount shouldBe 2

                    val remaining = tx.readOnly {
                        testEntityRepository.findByValueWithQuery(4000)
                    }
                    remaining.shouldBeEmpty()

                    val kept = tx.readOnly {
                        testEntityRepository.findByValueWithQuery(4001)
                    }
                    kept shouldHaveSize 1
                }

                it("returns zero when no entities are deleted") {
                    val deletedCount = tx.transactional {
                        testEntityRepository.deleteByValueWithQuery(777777)
                    }

                    deletedCount shouldBe 0
                }
            }

            context("@Query + Page") {

                it("combines @Query with Pageable") {
                    tx.transactional {
                        repeat(15) { i ->
                            testEntityRepository.save(TestEntity(name = "query-page-$i", value = 5000))
                        }
                    }

                    val page = tx.readOnly {
                        testEntityRepository.findByValueWithQueryPageable(5000, PageRequest.of(0, 5))
                    }

                    page.content shouldHaveSize 5
                    page.totalElements shouldBe 15
                    page.totalPages shouldBe 3
                    page.isFirst shouldBe true
                    page.hasNext() shouldBe true
                }

                it("returns the second page from @Query with Pageable") {
                    tx.transactional {
                        repeat(15) { i ->
                            testEntityRepository.save(TestEntity(name = "query-page2-$i", value = 5001))
                        }
                    }

                    val page = tx.readOnly {
                        testEntityRepository.findByValueWithQueryPageable(5001, PageRequest.of(1, 5))
                    }

                    page.content shouldHaveSize 5
                    page.number shouldBe 1
                    page.isFirst shouldBe false
                    page.isLast shouldBe false
                }
            }

            context("@Query + Slice") {

                it("reports whether a Slice has a next page") {
                    tx.transactional {
                        repeat(10) { i ->
                            testEntityRepository.save(TestEntity(name = "slice-test-$i", value = 6000 + i))
                        }
                    }

                    val slice = tx.readOnly {
                        testEntityRepository.findByValueGreaterThanWithQuerySlice(6002, PageRequest.of(0, 3))
                    }

                    slice.content shouldHaveSize 3
                    slice.hasNext() shouldBe true
                    slice.isFirst shouldBe true
                }

                it("identifies the final Slice page") {
                    tx.transactional {
                        repeat(5) { i ->
                            testEntityRepository.save(TestEntity(name = "slice-last-$i", value = 6100 + i))
                        }
                    }

                    val slice = tx.readOnly {
                        testEntityRepository.findByValueGreaterThanWithQuerySlice(6099, PageRequest.of(1, 3))
                    }

                    slice.content shouldHaveSize 2
                    slice.hasNext() shouldBe false
                    slice.isLast shouldBe true
                }
            }

            context("explicit countQuery") {

                it("returns the correct total through countQuery") {
                    tx.transactional {
                        repeat(12) { i ->
                            testEntityRepository.save(TestEntity(name = "explicit-count-$i", value = 7000))
                        }
                    }

                    val page = tx.readOnly {
                        testEntityRepository.findByValueWithExplicitCount(7000, PageRequest.of(0, 5))
                    }

                    page.content shouldHaveSize 5
                    page.totalElements shouldBe 12
                    page.totalPages shouldBe 3
                }

                it("paginates results ordered by the query") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "z-count", value = 7001))
                        testEntityRepository.save(TestEntity(name = "a-count", value = 7001))
                        testEntityRepository.save(TestEntity(name = "m-count", value = 7001))
                    }

                    val page = tx.readOnly {
                        testEntityRepository.findByValueWithExplicitCount(7001, PageRequest.of(0, 2))
                    }

                    page.content shouldHaveSize 2
                    page.content[0].name shouldBe "a-count"
                    page.content[1].name shouldBe "m-count"
                    page.totalElements shouldBe 3
                }
            }
        }
    }
}
