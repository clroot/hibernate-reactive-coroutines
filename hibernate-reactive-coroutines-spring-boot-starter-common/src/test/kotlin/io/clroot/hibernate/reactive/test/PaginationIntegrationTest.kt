package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort

@SpringBootTest(classes = [TestApplication::class])
class PaginationIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("Pagination") {

            context("findAllByValue(value, pageable) - basic pagination") {
                // Values below 1500 isolate these tests from QueryMethodIntegrationTest.
                val baseValue = 100

                it("retrieves the first page") {
                    val testValue = baseValue + 1
                    tx.transactional {
                        repeat(10) { i ->
                            testEntityRepository.save(
                                TestEntity(name = "page_test_first_${i.toString().padStart(2, '0')}", value = testValue)
                            )
                        }
                    }

                    val pageable = PageRequest.of(0, 3)

                    val page = tx.readOnly {
                        testEntityRepository.findAllByValue(testValue, pageable)
                    }

                    page.content shouldHaveSize 3
                    page.totalElements shouldBe 10
                    page.totalPages shouldBe 4
                    page.number shouldBe 0
                    page.hasNext() shouldBe true
                    page.hasPrevious() shouldBe false
                }

                it("retrieves the final page") {
                    val testValue = baseValue + 2
                    tx.transactional {
                        repeat(10) { i ->
                            testEntityRepository.save(
                                TestEntity(name = "page_test_last_${i.toString().padStart(2, '0')}", value = testValue)
                            )
                        }
                    }

                    val pageable = PageRequest.of(3, 3)

                    val page = tx.readOnly {
                        testEntityRepository.findAllByValue(testValue, pageable)
                    }

                    page.content shouldHaveSize 1
                    page.totalElements shouldBe 10
                    page.number shouldBe 3
                    page.hasNext() shouldBe false
                    page.hasPrevious() shouldBe true
                }

                it("applies sorting") {
                    val testValue = baseValue + 3
                    tx.transactional {
                        repeat(10) { i ->
                            testEntityRepository.save(
                                TestEntity(name = "page_test_sort_${i.toString().padStart(2, '0')}", value = testValue)
                            )
                        }
                    }

                    val pageable = PageRequest.of(0, 3, Sort.by(Sort.Direction.DESC, "name"))

                    val page = tx.readOnly {
                        testEntityRepository.findAllByValue(testValue, pageable)
                    }

                    page.content.map { it.name } shouldContainExactly listOf(
                        "page_test_sort_09", "page_test_sort_08", "page_test_sort_07"
                    )
                }
            }

            context("findAllByValueOrderByNameDesc - default sorting") {
                it("applies the method-name sort") {
                    val testValue = 200
                    tx.transactional {
                        repeat(10) { i ->
                            testEntityRepository.save(
                                TestEntity(name = "order_test_${i.toString().padStart(2, '0')}", value = testValue)
                            )
                        }
                    }

                    val list = tx.readOnly {
                        testEntityRepository.findAllByValueOrderByNameDesc(testValue)
                    }

                    list shouldHaveSize 10
                    list.first().name shouldBe "order_test_09"
                    list.last().name shouldBe "order_test_00"
                }
            }

            context("findAllByValue(value, pageable) - custom query + Page") {
                it("applies the predicate and pagination together") {
                    val testValue = 300
                    tx.transactional {
                        repeat(10) { i ->
                            testEntityRepository.save(
                                TestEntity(name = "custom_page_$i", value = testValue)
                            )
                        }
                    }

                    val pageable = PageRequest.of(0, 5)

                    val page = tx.readOnly {
                        testEntityRepository.findAllByValue(testValue, pageable)
                    }

                    page.content shouldHaveSize 5
                    page.totalElements shouldBe 10
                    page.content.all { it.value == testValue } shouldBe true
                }
            }

            context("findAllByValueGreaterThan(value, pageable) - Slice") {
                it("checks for a next page without a COUNT query") {
                    val sliceValue = 400
                    tx.transactional {
                        repeat(5) { i ->
                            testEntityRepository.save(
                                TestEntity(name = "slice_test_$i", value = sliceValue)
                            )
                        }
                    }

                    val pageable = PageRequest.of(0, 3)

                    val slice = tx.readOnly {
                        testEntityRepository.findAllByValueGreaterThan(sliceValue - 1, pageable)
                    }

                    slice.content shouldHaveSize 3
                    slice.hasNext() shouldBe true
                }

                it("sets hasNext to false on the final page") {
                    val lastSliceValue = 500
                    tx.transactional {
                        repeat(2) { i ->
                            testEntityRepository.save(
                                TestEntity(name = "slice_last_$i", value = lastSliceValue)
                            )
                        }
                    }

                    val pageable = PageRequest.of(0, 5)

                    val slice = tx.readOnly {
                        testEntityRepository.findAllByValueGreaterThan(lastSliceValue - 1, pageable)
                    }

                    slice.content shouldHaveSize 2
                    slice.hasNext() shouldBe false
                }
            }

            context("sorting precedence") {
                it("gives the Pageable Sort precedence over the method-name sort") {
                    val testValue = 600
                    tx.transactional {
                        repeat(10) { i ->
                            testEntityRepository.save(
                                TestEntity(name = "priority_asc_${i.toString().padStart(2, '0')}", value = testValue)
                            )
                        }
                    }

                    val pageable = PageRequest.of(0, 3, Sort.by(Sort.Direction.ASC, "name"))

                    val page = tx.readOnly {
                        testEntityRepository.findAllByValueOrderByNameDesc(testValue, pageable)
                    }

                    page.content.map { it.name } shouldContainExactly listOf(
                        "priority_asc_00", "priority_asc_01", "priority_asc_02"
                    )
                }

                it("uses method-name sorting when Pageable has no Sort") {
                    val testValue = 700
                    tx.transactional {
                        repeat(10) { i ->
                            testEntityRepository.save(
                                TestEntity(name = "priority_desc_${i.toString().padStart(2, '0')}", value = testValue)
                            )
                        }
                    }

                    val pageable = PageRequest.of(0, 3)

                    val page = tx.readOnly {
                        testEntityRepository.findAllByValueOrderByNameDesc(testValue, pageable)
                    }

                    page.content.map { it.name } shouldContainExactly listOf(
                        "priority_desc_09", "priority_desc_08", "priority_desc_07"
                    )
                }
            }

            context("smart skip optimization") {
                it("skips the COUNT query when the first page is also the final page") {
                    val smartSkipValue = 800
                    tx.transactional {
                        repeat(3) { i ->
                            testEntityRepository.save(
                                TestEntity(name = "smart_skip_$i", value = smartSkipValue)
                            )
                        }
                    }

                    val pageable = PageRequest.of(0, 10)

                    val page = tx.readOnly {
                        testEntityRepository.findAllByValue(smartSkipValue, pageable)
                    }

                    page.content shouldHaveSize 3
                    page.totalElements shouldBe 3
                }
            }

            context("empty results") {
                it("returns an empty Page when no data matches") {
                    val pageable = PageRequest.of(0, 10)

                    val page = tx.readOnly {
                        testEntityRepository.findAllByValue(999999, pageable)
                    }

                    page.content shouldHaveSize 0
                    page.totalElements shouldBe 0
                    page.hasNext() shouldBe false
                }
            }
        }
    }
}
