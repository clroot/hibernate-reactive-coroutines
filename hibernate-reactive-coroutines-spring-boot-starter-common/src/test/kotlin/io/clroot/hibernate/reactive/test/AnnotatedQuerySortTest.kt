package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.kotest.matchers.collections.shouldContainExactly
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort

/**
 * `@Query` 메서드에도 동적 정렬이 적용되는지 검증합니다.
 *
 * 정렬이 무시되면 페이징 결과 순서가 보장되지 않아 페이지 간 중복·누락이 발생합니다.
 */
@SpringBootTest
class AnnotatedQuerySortTest : IntegrationTestBase() {

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    init {
        beforeEach {
            tx.transactional {
                testEntityRepository.save(TestEntity(name = "b", value = 10))
                testEntityRepository.save(TestEntity(name = "a", value = 20))
                testEntityRepository.save(TestEntity(name = "c", value = 30))
            }
        }

        describe("@Query + Sort 파라미터") {

            it("Sort를 적용한다") {
                val sorted = tx.readOnly {
                    testEntityRepository.findByValueGreaterThanWithQuery(0, Sort.by("name"))
                }

                sorted.map { it.name } shouldContainExactly listOf("a", "b", "c")
            }

            it("쿼리에 이미 있는 ORDER BY를 보존하고 뒤에 덧붙인다") {
                val sorted = tx.readOnly {
                    testEntityRepository.findOrderedByValueGreaterThanWithQuery(0, Sort.by("name"))
                }

                // 쿼리의 value DESC가 우선 적용됩니다.
                sorted.map { it.name } shouldContainExactly listOf("c", "a", "b")
            }
        }

        describe("@Query + 정렬된 Pageable") {

            it("Page 조회에 Pageable의 Sort를 적용한다") {
                val page = tx.readOnly {
                    testEntityRepository.findByValueWithQueryPageable(
                        10,
                        PageRequest.of(0, 10, Sort.by("name")),
                    )
                }

                page.content.map { it.name } shouldContainExactly listOf("b")
            }

            it("Slice 조회에 Pageable의 Sort를 적용한다") {
                val slice = tx.readOnly {
                    testEntityRepository.findByValueGreaterThanWithQuerySlice(
                        0,
                        PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "name")),
                    )
                }

                slice.content.map { it.name } shouldContainExactly listOf("c", "b")
            }
        }
    }
}
