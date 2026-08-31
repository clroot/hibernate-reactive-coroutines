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
        describe("페이징") {

            context("findAllByValue(value, pageable) - 기본 페이징") {
                // 각 테스트에서 고유한 value 사용 (1500 미만으로 설정하여 QueryMethodIntegrationTest와 격리)
                val baseValue = 100

                it("첫 번째 페이지를 조회한다") {
                    val testValue = baseValue + 1
                    // 테스트 데이터 준비: 10개 엔티티
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

                it("마지막 페이지를 조회한다") {
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

                it("정렬을 적용한다") {
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

            context("findAllByValueOrderByNameDesc - 기본 정렬") {
                it("메서드명의 정렬을 적용하여 조회한다") {
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

            context("findAllByValue(value, pageable) - 커스텀 쿼리 + Page") {
                it("조건과 페이징을 함께 적용한다") {
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
                it("COUNT 없이 다음 페이지 여부만 확인한다") {
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

                it("마지막 페이지에서 hasNext는 false") {
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

            context("정렬 우선순위") {
                it("Pageable의 Sort가 메서드명 정렬보다 우선한다") {
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

                    // Pageable의 ASC가 적용되어야 함
                    page.content.map { it.name } shouldContainExactly listOf(
                        "priority_asc_00", "priority_asc_01", "priority_asc_02"
                    )
                }

                it("Pageable에 Sort가 없으면 메서드명 정렬을 적용한다") {
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

                    // 메서드명의 DESC가 적용되어야 함
                    page.content.map { it.name } shouldContainExactly listOf(
                        "priority_desc_09", "priority_desc_08", "priority_desc_07"
                    )
                }
            }

            context("스마트 스킵 최적화") {
                it("마지막 페이지에서는 COUNT 쿼리를 스킵한다") {
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

            context("빈 결과") {
                it("일치하는 데이터가 없으면 빈 Page를 반환한다") {
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
