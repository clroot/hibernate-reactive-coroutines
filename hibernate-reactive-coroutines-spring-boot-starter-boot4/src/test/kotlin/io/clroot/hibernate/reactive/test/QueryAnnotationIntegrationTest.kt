package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(classes = [TestApplication::class])
class QueryAnnotationIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    companion object {
        // 각 테스트에서 고유한 value를 사용하기 위한 카운터
        // 다른 테스트 클래스와 충돌하지 않도록 높은 값에서 시작
        private val valueCounter = AtomicInteger(50000)
    }

    init {
        describe("@Query 어노테이션") {

            context("Named Parameter + @Param") {
                it("@Param으로 지정한 이름으로 파라미터를 바인딩한다") {
                    val uniqueValue = valueCounter.incrementAndGet()

                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "query_named_1", value = uniqueValue))
                        testEntityRepository.save(TestEntity(name = "query_named_2", value = uniqueValue))
                        testEntityRepository.save(TestEntity(name = "query_named_3", value = uniqueValue + 1))
                    }

                    // when
                    val found = tx.readOnly {
                        testEntityRepository.findByValueWithQuery(uniqueValue)
                    }

                    // then
                    found shouldHaveSize 2
                    found.map { it.name } shouldContainExactlyInAnyOrder listOf("query_named_1", "query_named_2")
                }
            }

            context("Named Parameter (자동 추출)") {
                it("@Param 없이도 Kotlin 파라미터 이름으로 바인딩한다") {
                    val uniqueValue = valueCounter.incrementAndGet()
                    val uniqueName = "auto_param_$uniqueValue"

                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = uniqueName, value = uniqueValue))
                    }

                    // when
                    val found = tx.readOnly {
                        testEntityRepository.findByNameAndValueWithQuery(uniqueName, uniqueValue)
                    }

                    // then
                    found.shouldNotBeNull()
                    found.name shouldBe uniqueName
                    found.value shouldBe uniqueValue
                }

                it("일치하지 않으면 null 반환") {
                    val found = tx.readOnly {
                        testEntityRepository.findByNameAndValueWithQuery("nonexistent_${System.nanoTime()}", 999999)
                    }

                    found.shouldBeNull()
                }
            }

            context("Positional Parameter") {
                it("?1, ?2 형식으로 파라미터를 바인딩한다") {
                    val baseValue = valueCounter.addAndGet(100)

                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "positional_1", value = baseValue + 10))
                        testEntityRepository.save(TestEntity(name = "positional_2", value = baseValue + 20))
                        testEntityRepository.save(TestEntity(name = "positional_3", value = baseValue + 30))
                        testEntityRepository.save(TestEntity(name = "positional_4", value = baseValue + 40))
                    }

                    // when
                    val found = tx.readOnly {
                        testEntityRepository.findByValueBetweenWithQuery(baseValue + 15, baseValue + 35)
                    }

                    // then
                    found shouldHaveSize 2
                    found.map { it.value } shouldContainExactlyInAnyOrder listOf(baseValue + 20, baseValue + 30)
                }
            }

            context("@Modifying UPDATE") {
                it("UPDATE 쿼리를 실행하고 영향받은 행 수를 반환한다") {
                    val uniqueValue = valueCounter.incrementAndGet()
                    val newValue = uniqueValue + 50

                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "update_1", value = uniqueValue))
                        testEntityRepository.save(TestEntity(name = "update_2", value = uniqueValue))
                        testEntityRepository.save(TestEntity(name = "update_3", value = uniqueValue + 100))
                    }

                    // when
                    val affected = tx.transactional {
                        testEntityRepository.updateValue(uniqueValue, newValue)
                    }

                    // then
                    affected shouldBe 2

                    val updated = tx.readOnly {
                        testEntityRepository.findByValueWithQuery(newValue)
                    }
                    updated shouldHaveSize 2
                }
            }

            context("@Modifying DELETE") {
                it("DELETE 쿼리를 실행하고 영향받은 행 수를 반환한다") {
                    val uniqueValue = valueCounter.incrementAndGet()

                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "delete_1", value = uniqueValue))
                        testEntityRepository.save(TestEntity(name = "delete_2", value = uniqueValue))
                        testEntityRepository.save(TestEntity(name = "delete_3", value = uniqueValue + 100))
                    }

                    // when
                    val affected = tx.transactional {
                        testEntityRepository.deleteByValueWithQuery(uniqueValue)
                    }

                    // then
                    affected shouldBe 2

                    val remaining = tx.readOnly {
                        testEntityRepository.findByValueWithQuery(uniqueValue)
                    }
                    remaining shouldHaveSize 0
                }
            }

            context("@Query + Page") {
                it("페이징과 함께 쿼리를 실행한다") {
                    val uniqueValue = valueCounter.incrementAndGet()

                    // given
                    tx.transactional {
                        repeat(10) { i ->
                            testEntityRepository.save(TestEntity(name = "page_query_$i", value = uniqueValue))
                        }
                    }

                    // when
                    val page = tx.readOnly {
                        testEntityRepository.findByValueWithQueryPageable(uniqueValue, PageRequest.of(0, 3))
                    }

                    // then
                    page.content shouldHaveSize 3
                    page.totalElements shouldBe 10
                    page.totalPages shouldBe 4
                }
            }

            context("@Query + Slice") {
                it("Slice로 다음 페이지 여부를 확인한다") {
                    val baseValue = valueCounter.addAndGet(100)

                    // given
                    tx.transactional {
                        repeat(5) { i ->
                            testEntityRepository.save(TestEntity(name = "slice_query_$i", value = baseValue + i + 1))
                        }
                    }

                    // when
                    val slice = tx.readOnly {
                        testEntityRepository.findByValueGreaterThanWithQuerySlice(baseValue, PageRequest.of(0, 3))
                    }

                    // then
                    slice.content shouldHaveSize 3
                    slice.hasNext() shouldBe true
                }
            }

            context("@Query + 명시적 countQuery") {
                it("명시적으로 지정한 countQuery를 사용한다") {
                    val uniqueValue = valueCounter.addAndGet(100)  // 큰 간격으로 증가

                    // given
                    tx.transactional {
                        repeat(7) { i ->
                            testEntityRepository.save(TestEntity(name = "explicit_count_$i", value = uniqueValue))
                        }
                    }

                    // when
                    val page = tx.readOnly {
                        testEntityRepository.findByValueWithExplicitCount(uniqueValue, PageRequest.of(0, 5))
                    }

                    // then
                    page.content shouldHaveSize 5
                    page.totalElements shouldBe 7
                }
            }
        }
    }
}
