package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort

/**
 * Tests pagination edge cases.
 *
 * Covers empty results, final pages, large datasets, and complex sorting.
 */
@SpringBootTest(classes = [TestApplication::class])
class PaginationEdgeCasesTest : IntegrationTestBase() {

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("Pagination Edge Cases") {

            context("empty results") {

                it("returns an empty Page when no data matches") {
                    val page = tx.readOnly {
                        testEntityRepository.findAllByValue(999999, PageRequest.of(0, 10))
                    }

                    page.content.shouldBeEmpty()
                    page.totalElements shouldBe 0
                    page.totalPages shouldBe 0
                    page.isEmpty shouldBe true
                }

                it("sets hasNext to false for empty results") {
                    val page = tx.readOnly {
                        testEntityRepository.findAllByValue(888888, PageRequest.of(0, 10))
                    }

                    page.hasNext().shouldBeFalse()
                    page.hasPrevious().shouldBeFalse()
                    page.isFirst.shouldBeTrue()
                    page.isLast.shouldBeTrue()
                }

                it("handles an empty Slice") {
                    val slice = tx.readOnly {
                        testEntityRepository.findAllByValueGreaterThan(999999, PageRequest.of(0, 10))
                    }

                    slice.content.shouldBeEmpty()
                    slice.hasNext().shouldBeFalse()
                }
            }

            context("the final page") {

                it("sets hasNext to false") {
                    tx.transactional {
                        repeat(7) { i ->
                            testEntityRepository.save(TestEntity(name = "last-page-$i", value = 8001))
                        }
                    }

                    val page = tx.readOnly {
                        testEntityRepository.findAllByValue(8001, PageRequest.of(2, 3))
                    }

                    page.content shouldHaveSize 1
                    page.hasNext().shouldBeFalse()
                    page.isLast.shouldBeTrue()
                    page.totalPages shouldBe 3
                }

                it("handles totals evenly divisible by the page size") {
                    tx.transactional {
                        repeat(9) { i ->
                            testEntityRepository.save(TestEntity(name = "exact-div-$i", value = 8002))
                        }
                    }

                    val page = tx.readOnly {
                        testEntityRepository.findAllByValue(8002, PageRequest.of(2, 3))
                    }

                    page.content shouldHaveSize 3
                    page.hasNext().shouldBeFalse()
                    page.isLast.shouldBeTrue()
                }
            }

            context("when the offset exceeds the total count") {

                it("returns an empty Page for a nonexistent page request") {
                    // A UUID-derived value isolates this test from rows created by other tests.
                    val uniqueValue = java.util.UUID.randomUUID().hashCode()

                    tx.transactional {
                        repeat(5) { i ->
                            testEntityRepository.save(TestEntity(name = "over-offset-nano-$i", value = uniqueValue))
                        }
                    }

                    val page = tx.readOnly {
                        testEntityRepository.findAllByValue(uniqueValue, PageRequest.of(100, 3))
                    }

                    page.content.shouldBeEmpty()
                    page.totalElements shouldBe 5
                    page.totalPages shouldBe 2
                }

                it("returns an empty Slice") {
                    val slice = tx.readOnly {
                        testEntityRepository.findAllByValueGreaterThan(9999999, PageRequest.of(0, 10))
                    }

                    slice.content.shouldBeEmpty()
                    slice.hasNext().shouldBeFalse()
                }

                it("rejects offsets beyond the Int range instead of truncating them") {
                    shouldThrow<IllegalArgumentException> {
                        tx.readOnly {
                            testEntityRepository.findAll(PageRequest.of(Int.MAX_VALUE, 2))
                        }
                    }
                }
            }

            context("large dataset pagination") {

                it("paginates 100 records in groups of 10") {
                    tx.transactional {
                        repeat(100) { i ->
                            testEntityRepository.save(TestEntity(name = "large-$i", value = 8005))
                        }
                    }

                    val firstPage = tx.readOnly {
                        testEntityRepository.findAllByValue(8005, PageRequest.of(0, 10))
                    }

                    firstPage.content shouldHaveSize 10
                    firstPage.totalElements shouldBe 100
                    firstPage.totalPages shouldBe 10
                    firstPage.hasNext().shouldBeTrue()

                    val lastPage = tx.readOnly {
                        testEntityRepository.findAllByValue(8005, PageRequest.of(9, 10))
                    }

                    lastPage.content shouldHaveSize 10
                    lastPage.hasNext().shouldBeFalse()
                    lastPage.isLast.shouldBeTrue()
                }

                it("retrieves a middle page from 200 records") {
                    tx.transactional {
                        repeat(200) { i ->
                            testEntityRepository.save(TestEntity(name = "mid-page-$i", value = 8006))
                        }
                    }

                    val midPage = tx.readOnly {
                        testEntityRepository.findAllByValue(8006, PageRequest.of(5, 20))
                    }

                    midPage.content shouldHaveSize 20
                    midPage.number shouldBe 5
                    midPage.hasNext().shouldBeTrue()
                    midPage.hasPrevious().shouldBeTrue()
                }
            }

            context("sorting combinations") {

                it("sorts using a Sort parameter") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "sort-c", value = 8007))
                        testEntityRepository.save(TestEntity(name = "sort-a", value = 8007))
                        testEntityRepository.save(TestEntity(name = "sort-b", value = 8007))
                    }

                    val ascPage = tx.readOnly {
                        testEntityRepository.findAllByValue(8007, PageRequest.of(0, 10, Sort.by("name").ascending()))
                    }

                    ascPage.content.map { it.name } shouldBe listOf("sort-a", "sort-b", "sort-c")

                    val descPage = tx.readOnly {
                        testEntityRepository.findAllByValue(8007, PageRequest.of(0, 10, Sort.by("name").descending()))
                    }

                    descPage.content.map { it.name } shouldBe listOf("sort-c", "sort-b", "sort-a")
                }

                it("sorts by multiple columns") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "multi-sort-a", value = 100))
                        testEntityRepository.save(TestEntity(name = "multi-sort-b", value = 100))
                        testEntityRepository.save(TestEntity(name = "multi-sort-a", value = 200))
                    }

                    val sorted = tx.readOnly {
                        testEntityRepository.findAll(
                            PageRequest.of(0, 10, Sort.by("value").descending().and(Sort.by("name").ascending()))
                        )
                    }

                    val filtered = sorted.content.filter { it.name.startsWith("multi-sort-") }
                    filtered shouldHaveSize 3
                    filtered[0].let { it.name shouldBe "multi-sort-a"; it.value shouldBe 200 }
                    filtered[1].let { it.name shouldBe "multi-sort-a"; it.value shouldBe 100 }
                    filtered[2].let { it.name shouldBe "multi-sort-b"; it.value shouldBe 100 }
                }

                it("combines method-name sorting with pagination") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "method-sort-z", value = 8008))
                        testEntityRepository.save(TestEntity(name = "method-sort-a", value = 8008))
                        testEntityRepository.save(TestEntity(name = "method-sort-m", value = 8008))
                    }

                    val page = tx.readOnly {
                        testEntityRepository.findAllByValueOrderByNameDesc(8008, PageRequest.of(0, 2))
                    }

                    page.content shouldHaveSize 2
                    page.content[0].name shouldBe "method-sort-z"
                    page.content[1].name shouldBe "method-sort-m"
                    page.hasNext().shouldBeTrue()
                }
            }

            context("Slice vs Page") {

                it("uses Slice for results that do not require a total count") {
                    tx.transactional {
                        repeat(50) { i ->
                            testEntityRepository.save(TestEntity(name = "slice-eff-$i", value = 8009))
                        }
                    }

                    val slice = tx.readOnly {
                        testEntityRepository.findAllByValueGreaterThan(8008, PageRequest.of(0, 10))
                    }

                    slice.content shouldHaveSize 10
                    slice.hasNext().shouldBeTrue()
                }

                it("includes the total count in a Page") {
                    tx.transactional {
                        repeat(25) { i ->
                            testEntityRepository.save(TestEntity(name = "page-total-$i", value = 8010))
                        }
                    }

                    val page = tx.readOnly {
                        testEntityRepository.findAllByValue(8010, PageRequest.of(0, 10))
                    }

                    page.content shouldHaveSize 10
                    page.totalElements shouldBe 25
                    page.totalPages shouldBe 3
                }
            }

            context("findAll pagination") {

                it("paginates all records") {
                    tx.transactional {
                        repeat(15) { i ->
                            testEntityRepository.save(TestEntity(name = "findall-page-$i", value = 8011))
                        }
                    }

                    val page = tx.readOnly {
                        testEntityRepository.findAll(PageRequest.of(1, 5))
                    }

                    page.content shouldHaveSize 5
                    page.number shouldBe 1
                }

                it("sorts all records") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "findall-sort-b", value = 1))
                        testEntityRepository.save(TestEntity(name = "findall-sort-a", value = 2))
                        testEntityRepository.save(TestEntity(name = "findall-sort-c", value = 3))
                    }

                    val sorted = tx.readOnly {
                        testEntityRepository.findAll(Sort.by("name").ascending())
                    }

                    val filtered = sorted.filter { it.name.startsWith("findall-sort-") }
                    filtered.map { it.name } shouldBe listOf("findall-sort-a", "findall-sort-b", "findall-sort-c")
                }

                it("sorts string properties case-insensitively with LOWER") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "case-sort-apple", value = 1))
                        testEntityRepository.save(TestEntity(name = "case-sort-Banana", value = 2))
                        testEntityRepository.save(TestEntity(name = "case-sort-cherry", value = 3))
                    }

                    val sorted = tx.readOnly {
                        testEntityRepository.findAll(Sort.by(Sort.Order.by("name").ignoreCase()))
                    }

                    sorted.filter { it.name.startsWith("case-sort-", ignoreCase = true) }
                        .map { it.name } shouldBe
                            listOf("case-sort-apple", "case-sort-Banana", "case-sort-cherry")
                }

                it("rejects case-insensitive sorting for non-string properties") {
                    shouldThrow<IllegalArgumentException> {
                        tx.readOnly {
                            testEntityRepository.findAll(Sort.by(Sort.Order.by("value").ignoreCase()))
                        }
                    }
                }
            }
        }
    }
}
