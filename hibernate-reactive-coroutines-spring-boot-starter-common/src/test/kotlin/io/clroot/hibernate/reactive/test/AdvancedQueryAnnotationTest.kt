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
 * @Query 어노테이션 고급 기능 테스트.
 *
 * Named/Positional 파라미터, @Modifying, 페이지네이션 조합을 검증합니다.
 */
@SpringBootTest(classes = [TestApplication::class])
class AdvancedQueryAnnotationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("@Query 고급 기능") {

            context("Named Parameter (@Param)") {

                it("단일 Named Parameter로 조회") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "named-param-1", value = 1001))
                        testEntityRepository.save(TestEntity(name = "named-param-2", value = 1001))
                        testEntityRepository.save(TestEntity(name = "named-param-3", value = 1002))
                    }

                    // when
                    val found = tx.readOnly {
                        testEntityRepository.findByValueWithQuery(1001)
                    }

                    // then
                    found shouldHaveSize 2
                    found.map { it.name } shouldContainExactlyInAnyOrder listOf("named-param-1", "named-param-2")
                }

                it("복수 Named Parameter로 조회") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "multi-param", value = 2001))
                        testEntityRepository.save(TestEntity(name = "multi-param", value = 2002))
                        testEntityRepository.save(TestEntity(name = "other-name", value = 2001))
                    }

                    // when
                    val found = tx.readOnly {
                        testEntityRepository.findByNameAndValueWithQuery("multi-param", 2001)
                    }

                    // then
                    found.shouldNotBeNull()
                    found.name shouldBe "multi-param"
                    found.value shouldBe 2001
                }

                it("Named Parameter 결과 없음") {
                    val found = tx.readOnly {
                        testEntityRepository.findByNameAndValueWithQuery("non-existent", 9999)
                    }

                    found.shouldBeNull()
                }
            }

            context("Positional Parameter") {

                it("범위 조건으로 조회 (BETWEEN 대체)") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "range-1", value = 10))
                        testEntityRepository.save(TestEntity(name = "range-2", value = 20))
                        testEntityRepository.save(TestEntity(name = "range-3", value = 30))
                        testEntityRepository.save(TestEntity(name = "range-4", value = 40))
                    }

                    // when - value > 15 AND value < 35
                    val found = tx.readOnly {
                        testEntityRepository.findByValueBetweenWithQuery(15, 35)
                    }

                    // then
                    found shouldHaveSize 2
                    found.map { it.value } shouldContainExactlyInAnyOrder listOf(20, 30)
                }

                it("경계값 테스트 (exclusive)") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "boundary-pos-10", value = 10))
                        testEntityRepository.save(TestEntity(name = "boundary-pos-20", value = 20))
                    }

                    // when - value > 10 AND value < 20 → 결과 없음
                    val found = tx.readOnly {
                        testEntityRepository.findByValueBetweenWithQuery(10, 20)
                    }

                    // then - 10과 20 모두 제외
                    found.shouldBeEmpty()
                }
            }

            context("@Modifying UPDATE") {

                it("값 일괄 업데이트") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "update-target-1", value = 3000))
                        testEntityRepository.save(TestEntity(name = "update-target-2", value = 3000))
                        testEntityRepository.save(TestEntity(name = "update-target-3", value = 3001))
                    }

                    // when
                    val updatedCount = tx.transactional {
                        testEntityRepository.updateValue(3000, 3999)
                    }

                    // then
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

                it("업데이트 대상 없으면 0 반환") {
                    val updatedCount = tx.transactional {
                        testEntityRepository.updateValue(999999, 888888)
                    }

                    updatedCount shouldBe 0
                }
            }

            context("@Modifying DELETE") {

                it("조건에 맞는 데이터 삭제") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "delete-query-1", value = 4000))
                        testEntityRepository.save(TestEntity(name = "delete-query-2", value = 4000))
                        testEntityRepository.save(TestEntity(name = "keep-query-1", value = 4001))
                    }

                    // when
                    val deletedCount = tx.transactional {
                        testEntityRepository.deleteByValueWithQuery(4000)
                    }

                    // then
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

                it("삭제 대상 없으면 0 반환") {
                    val deletedCount = tx.transactional {
                        testEntityRepository.deleteByValueWithQuery(777777)
                    }

                    deletedCount shouldBe 0
                }
            }

            context("@Query + Page") {

                it("@Query와 Pageable 조합") {
                    // given
                    tx.transactional {
                        repeat(15) { i ->
                            testEntityRepository.save(TestEntity(name = "query-page-$i", value = 5000))
                        }
                    }

                    // when
                    val page = tx.readOnly {
                        testEntityRepository.findByValueWithQueryPageable(5000, PageRequest.of(0, 5))
                    }

                    // then
                    page.content shouldHaveSize 5
                    page.totalElements shouldBe 15
                    page.totalPages shouldBe 3
                    page.isFirst shouldBe true
                    page.hasNext() shouldBe true
                }

                it("@Query와 Pageable - 두 번째 페이지") {
                    // given
                    tx.transactional {
                        repeat(15) { i ->
                            testEntityRepository.save(TestEntity(name = "query-page2-$i", value = 5001))
                        }
                    }

                    // when
                    val page = tx.readOnly {
                        testEntityRepository.findByValueWithQueryPageable(5001, PageRequest.of(1, 5))
                    }

                    // then
                    page.content shouldHaveSize 5
                    page.number shouldBe 1
                    page.isFirst shouldBe false
                    page.isLast shouldBe false
                }
            }

            context("@Query + Slice") {

                it("Slice로 다음 페이지 존재 여부 확인") {
                    // given
                    tx.transactional {
                        repeat(10) { i ->
                            testEntityRepository.save(TestEntity(name = "slice-test-$i", value = 6000 + i))
                        }
                    }

                    // when - value > 6002인 것 조회 (6003~6009 = 7개)
                    val slice = tx.readOnly {
                        testEntityRepository.findByValueGreaterThanWithQuerySlice(6002, PageRequest.of(0, 3))
                    }

                    // then
                    slice.content shouldHaveSize 3
                    slice.hasNext() shouldBe true
                    slice.isFirst shouldBe true
                }

                it("Slice 마지막 페이지") {
                    // given
                    tx.transactional {
                        repeat(5) { i ->
                            testEntityRepository.save(TestEntity(name = "slice-last-$i", value = 6100 + i))
                        }
                    }

                    // when - value > 6099인 것 조회 (5개), 마지막 페이지
                    val slice = tx.readOnly {
                        testEntityRepository.findByValueGreaterThanWithQuerySlice(6099, PageRequest.of(1, 3))
                    }

                    // then
                    slice.content shouldHaveSize 2 // 5개 중 마지막 2개
                    slice.hasNext() shouldBe false
                    slice.isLast shouldBe true
                }
            }

            context("명시적 countQuery") {

                it("countQuery가 정확한 총 개수를 반환") {
                    // given
                    tx.transactional {
                        repeat(12) { i ->
                            testEntityRepository.save(TestEntity(name = "explicit-count-$i", value = 7000))
                        }
                    }

                    // when
                    val page = tx.readOnly {
                        testEntityRepository.findByValueWithExplicitCount(7000, PageRequest.of(0, 5))
                    }

                    // then
                    page.content shouldHaveSize 5
                    page.totalElements shouldBe 12
                    page.totalPages shouldBe 3
                }

                it("countQuery로 정렬된 결과 페이징") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "z-count", value = 7001))
                        testEntityRepository.save(TestEntity(name = "a-count", value = 7001))
                        testEntityRepository.save(TestEntity(name = "m-count", value = 7001))
                    }

                    // when - ORDER BY name이 쿼리에 포함됨
                    val page = tx.readOnly {
                        testEntityRepository.findByValueWithExplicitCount(7001, PageRequest.of(0, 2))
                    }

                    // then - 이름순 정렬
                    page.content shouldHaveSize 2
                    page.content[0].name shouldBe "a-count"
                    page.content[1].name shouldBe "m-count"
                    page.totalElements shouldBe 3
                }
            }
        }
    }
}
