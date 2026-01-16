package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort

/**
 * 페이지네이션 Edge Case 테스트.
 *
 * 빈 결과, 마지막 페이지, 대용량 데이터, 복잡한 정렬 등을 검증합니다.
 */
@SpringBootTest(classes = [TestApplication::class])
class PaginationEdgeCasesTest : IntegrationTestBase() {

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("페이지네이션 Edge Cases") {

            context("빈 결과") {

                it("조건에 맞는 데이터가 없으면 빈 Page 반환") {
                    val page = tx.readOnly {
                        testEntityRepository.findAllByValue(999999, PageRequest.of(0, 10))
                    }

                    page.content.shouldBeEmpty()
                    page.totalElements shouldBe 0
                    page.totalPages shouldBe 0
                    page.isEmpty shouldBe true
                }

                it("빈 결과에서 hasNext는 false") {
                    val page = tx.readOnly {
                        testEntityRepository.findAllByValue(888888, PageRequest.of(0, 10))
                    }

                    page.hasNext().shouldBeFalse()
                    page.hasPrevious().shouldBeFalse()
                    page.isFirst.shouldBeTrue()
                    page.isLast.shouldBeTrue()
                }

                it("빈 Slice도 정상 동작") {
                    val slice = tx.readOnly {
                        testEntityRepository.findAllByValueGreaterThan(999999, PageRequest.of(0, 10))
                    }

                    slice.content.shouldBeEmpty()
                    slice.hasNext().shouldBeFalse()
                }
            }

            context("마지막 페이지") {

                it("마지막 페이지에서 hasNext는 false") {
                    // given - 7개 데이터
                    tx.transactional {
                        repeat(7) { i ->
                            testEntityRepository.save(TestEntity(name = "last-page-$i", value = 8001))
                        }
                    }

                    // when - 페이지 크기 3, 마지막(3번째) 페이지
                    val page = tx.readOnly {
                        testEntityRepository.findAllByValue(8001, PageRequest.of(2, 3))
                    }

                    // then
                    page.content shouldHaveSize 1 // 7번째 요소만
                    page.hasNext().shouldBeFalse()
                    page.isLast.shouldBeTrue()
                    page.totalPages shouldBe 3
                }

                it("정확히 페이지 크기로 나눠지는 경우") {
                    // given - 9개 데이터 (3페이지 x 3개씩)
                    tx.transactional {
                        repeat(9) { i ->
                            testEntityRepository.save(TestEntity(name = "exact-div-$i", value = 8002))
                        }
                    }

                    // when - 마지막 페이지
                    val page = tx.readOnly {
                        testEntityRepository.findAllByValue(8002, PageRequest.of(2, 3))
                    }

                    // then
                    page.content shouldHaveSize 3
                    page.hasNext().shouldBeFalse()
                    page.isLast.shouldBeTrue()
                }
            }

            context("offset이 전체 개수보다 큰 경우") {

                it("존재하지 않는 페이지 요청 시 빈 Page 반환") {
                    // given - 고유한 값 사용 (UUID 기반)
                    val uniqueValue = java.util.UUID.randomUUID().hashCode()

                    tx.transactional {
                        repeat(5) { i ->
                            testEntityRepository.save(TestEntity(name = "over-offset-nano-$i", value = uniqueValue))
                        }
                    }

                    // when - 존재하지 않는 페이지 요청
                    val page = tx.readOnly {
                        testEntityRepository.findAllByValue(uniqueValue, PageRequest.of(100, 3))
                    }

                    // then - 5개 데이터에 대해 100번째 페이지는 존재하지 않음
                    page.content.shouldBeEmpty()
                    page.totalElements shouldBe 5
                    page.totalPages shouldBe 2 // 5개를 3개씩 = 2페이지
                }

                it("Slice도 빈 결과 반환") {
                    // when - 존재하지 않는 범위의 값
                    val slice = tx.readOnly {
                        testEntityRepository.findAllByValueGreaterThan(9999999, PageRequest.of(0, 10))
                    }

                    // then
                    slice.content.shouldBeEmpty()
                    slice.hasNext().shouldBeFalse()
                }
            }

            context("대용량 데이터 페이징") {

                it("100개 데이터를 10개씩 페이징") {
                    // given
                    tx.transactional {
                        repeat(100) { i ->
                            testEntityRepository.save(TestEntity(name = "large-$i", value = 8005))
                        }
                    }

                    // when - 첫 페이지
                    val firstPage = tx.readOnly {
                        testEntityRepository.findAllByValue(8005, PageRequest.of(0, 10))
                    }

                    // then
                    firstPage.content shouldHaveSize 10
                    firstPage.totalElements shouldBe 100
                    firstPage.totalPages shouldBe 10
                    firstPage.hasNext().shouldBeTrue()

                    // when - 마지막 페이지
                    val lastPage = tx.readOnly {
                        testEntityRepository.findAllByValue(8005, PageRequest.of(9, 10))
                    }

                    // then
                    lastPage.content shouldHaveSize 10
                    lastPage.hasNext().shouldBeFalse()
                    lastPage.isLast.shouldBeTrue()
                }

                it("200개 데이터 중간 페이지 조회") {
                    // given
                    tx.transactional {
                        repeat(200) { i ->
                            testEntityRepository.save(TestEntity(name = "mid-page-$i", value = 8006))
                        }
                    }

                    // when - 5번째 페이지 (0-indexed)
                    val midPage = tx.readOnly {
                        testEntityRepository.findAllByValue(8006, PageRequest.of(5, 20))
                    }

                    // then
                    midPage.content shouldHaveSize 20
                    midPage.number shouldBe 5
                    midPage.hasNext().shouldBeTrue()
                    midPage.hasPrevious().shouldBeTrue()
                }
            }

            context("정렬 조합") {

                it("Sort 파라미터로 정렬") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "sort-c", value = 8007))
                        testEntityRepository.save(TestEntity(name = "sort-a", value = 8007))
                        testEntityRepository.save(TestEntity(name = "sort-b", value = 8007))
                    }

                    // when - 이름 오름차순
                    val ascPage = tx.readOnly {
                        testEntityRepository.findAllByValue(8007, PageRequest.of(0, 10, Sort.by("name").ascending()))
                    }

                    // then
                    ascPage.content.map { it.name } shouldBe listOf("sort-a", "sort-b", "sort-c")

                    // when - 이름 내림차순
                    val descPage = tx.readOnly {
                        testEntityRepository.findAllByValue(8007, PageRequest.of(0, 10, Sort.by("name").descending()))
                    }

                    // then
                    descPage.content.map { it.name } shouldBe listOf("sort-c", "sort-b", "sort-a")
                }

                it("복합 정렬 (다중 컬럼)") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "multi-sort-a", value = 100))
                        testEntityRepository.save(TestEntity(name = "multi-sort-b", value = 100))
                        testEntityRepository.save(TestEntity(name = "multi-sort-a", value = 200))
                    }

                    // when - value 내림차순 → name 오름차순
                    val sorted = tx.readOnly {
                        testEntityRepository.findAll(
                            PageRequest.of(0, 10, Sort.by("value").descending().and(Sort.by("name").ascending()))
                        )
                    }

                    // then - value=200 먼저, 그 다음 value=100에서 name 오름차순
                    val filtered = sorted.content.filter { it.name.startsWith("multi-sort-") }
                    filtered shouldHaveSize 3
                    filtered[0].let { it.name shouldBe "multi-sort-a"; it.value shouldBe 200 }
                    filtered[1].let { it.name shouldBe "multi-sort-a"; it.value shouldBe 100 }
                    filtered[2].let { it.name shouldBe "multi-sort-b"; it.value shouldBe 100 }
                }

                it("메서드명 정렬과 페이징 조합") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "method-sort-z", value = 8008))
                        testEntityRepository.save(TestEntity(name = "method-sort-a", value = 8008))
                        testEntityRepository.save(TestEntity(name = "method-sort-m", value = 8008))
                    }

                    // when - findAllByValueOrderByNameDesc + 페이징
                    val page = tx.readOnly {
                        testEntityRepository.findAllByValueOrderByNameDesc(8008, PageRequest.of(0, 2))
                    }

                    // then - 이름 내림차순으로 첫 2개
                    page.content shouldHaveSize 2
                    page.content[0].name shouldBe "method-sort-z"
                    page.content[1].name shouldBe "method-sort-m"
                    page.hasNext().shouldBeTrue()
                }
            }

            context("Slice vs Page") {

                it("Slice는 totalElements를 계산하지 않아 더 효율적") {
                    // given
                    tx.transactional {
                        repeat(50) { i ->
                            testEntityRepository.save(TestEntity(name = "slice-eff-$i", value = 8009))
                        }
                    }

                    // when
                    val slice = tx.readOnly {
                        testEntityRepository.findAllByValueGreaterThan(8008, PageRequest.of(0, 10))
                    }

                    // then - Slice는 다음 페이지 존재 여부만 확인
                    slice.content shouldHaveSize 10
                    slice.hasNext().shouldBeTrue()
                    // Slice는 totalElements, totalPages를 제공하지 않음
                }

                it("Page는 totalElements를 포함") {
                    // given
                    tx.transactional {
                        repeat(25) { i ->
                            testEntityRepository.save(TestEntity(name = "page-total-$i", value = 8010))
                        }
                    }

                    // when
                    val page = tx.readOnly {
                        testEntityRepository.findAllByValue(8010, PageRequest.of(0, 10))
                    }

                    // then
                    page.content shouldHaveSize 10
                    page.totalElements shouldBe 25
                    page.totalPages shouldBe 3
                }
            }

            context("findAll 페이징") {

                it("전체 조회 페이징") {
                    // given
                    tx.transactional {
                        repeat(15) { i ->
                            testEntityRepository.save(TestEntity(name = "findall-page-$i", value = 8011))
                        }
                    }

                    // when
                    val page = tx.readOnly {
                        testEntityRepository.findAll(PageRequest.of(1, 5))
                    }

                    // then
                    page.content shouldHaveSize 5
                    page.number shouldBe 1
                }

                it("전체 조회 정렬") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "findall-sort-b", value = 1))
                        testEntityRepository.save(TestEntity(name = "findall-sort-a", value = 2))
                        testEntityRepository.save(TestEntity(name = "findall-sort-c", value = 3))
                    }

                    // when
                    val sorted = tx.readOnly {
                        testEntityRepository.findAll(Sort.by("name").ascending())
                    }

                    // then
                    val filtered = sorted.filter { it.name.startsWith("findall-sort-") }
                    filtered.map { it.name } shouldBe listOf("findall-sort-a", "findall-sort-b", "findall-sort-c")
                }
            }
        }
    }
}
