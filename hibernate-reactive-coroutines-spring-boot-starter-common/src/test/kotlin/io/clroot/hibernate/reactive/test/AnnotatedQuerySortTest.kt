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
 * Verifies that dynamic sorting applies to `@Query` methods.
 *
 * Ignoring sort order makes paginated results unstable, which can duplicate or omit entities across pages.
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

        describe("@Query with Sort parameters") {

            it("applies Sort") {
                val sorted = tx.readOnly {
                    testEntityRepository.findByValueGreaterThanWithQuery(0, Sort.by("name"))
                }

                sorted.map { it.name } shouldContainExactly listOf("a", "b", "c")
            }

            it("preserves and appends to an existing query ORDER BY") {
                val sorted = tx.readOnly {
                    testEntityRepository.findOrderedByValueGreaterThanWithQuery(0, Sort.by("name"))
                }

                // The query's value DESC ordering takes precedence.
                sorted.map { it.name } shouldContainExactly listOf("c", "a", "b")
            }
        }

        describe("@Query with sorted Pageable") {

            it("applies Pageable Sort to a Page query") {
                val page = tx.readOnly {
                    testEntityRepository.findByValueWithQueryPageable(
                        10,
                        PageRequest.of(0, 10, Sort.by("name")),
                    )
                }

                page.content.map { it.name } shouldContainExactly listOf("b")
            }

            it("applies Pageable Sort to a Slice query") {
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
