package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.projection.TestEntitySummary
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest

@SpringBootTest(classes = [TestApplication::class])
class ProjectionQueryIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var repository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("@Query projections") {
            it("returns an aggregate scalar") {
                tx.transactional {
                    repository.save(TestEntity(name = "aggregate-1", value = 3800))
                    repository.save(TestEntity(name = "aggregate-2", value = 3801))
                    repository.save(TestEntity(name = "aggregate-3", value = 3802))
                }

                val count = tx.readOnly {
                    repository.countProjectedByMinValue(3801)
                }

                count shouldBe 2L
            }

            it("returns a list of scalar values") {
                tx.transactional {
                    repository.save(TestEntity(name = "scalar-c", value = 3810))
                    repository.save(TestEntity(name = "scalar-a", value = 3811))
                    repository.save(TestEntity(name = "scalar-b", value = 3812))
                }

                val names = tx.readOnly {
                    repository.findProjectedNamesByMinValue(3810)
                }

                names shouldContainExactly listOf("scalar-a", "scalar-b", "scalar-c")
            }

            it("returns single and list constructor DTO projections") {
                tx.transactional {
                    repository.save(TestEntity(name = "dto-a", value = 3820))
                    repository.save(TestEntity(name = "dto-b", value = 3821))
                }

                val single = tx.readOnly {
                    repository.findProjectedSummaryByName("dto-a")
                }
                val summaries = tx.readOnly {
                    repository.findProjectedSummariesByMinValue(3820)
                }

                single.shouldNotBeNull()
                single shouldBe TestEntitySummary("dto-a", 3820)
                summaries shouldContainExactly listOf(
                    TestEntitySummary("dto-a", 3820),
                    TestEntitySummary("dto-b", 3821),
                )
            }

            it("returns Page and Slice constructor DTO projections") {
                tx.transactional {
                    repeat(5) { index ->
                        repository.save(TestEntity(name = "page-$index", value = 3830))
                    }
                }

                val page = tx.readOnly {
                    repository.findProjectedSummariesByValue(3830, PageRequest.of(1, 2))
                }
                val slice = tx.readOnly {
                    repository.findProjectedSummarySliceByValue(3830, PageRequest.of(1, 2))
                }

                page.content shouldContainExactly listOf(
                    TestEntitySummary("page-2", 3830),
                    TestEntitySummary("page-3", 3830),
                )
                page.totalElements shouldBe 5L
                page.hasNext() shouldBe true

                slice.content shouldContainExactly page.content
                slice.hasNext() shouldBe true
            }
        }
    }
}
