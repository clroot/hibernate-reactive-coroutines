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
        // Start outside the ranges used by other integration tests.
        private val valueCounter = AtomicInteger(50000)
    }

    init {
        describe("@Query annotation") {

            context("Named Parameter + @Param") {
                it("binds parameters by the name declared with @Param") {
                    val uniqueValue = valueCounter.incrementAndGet()

                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "query_named_1", value = uniqueValue))
                        testEntityRepository.save(TestEntity(name = "query_named_2", value = uniqueValue))
                        testEntityRepository.save(TestEntity(name = "query_named_3", value = uniqueValue + 1))
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findByValueWithQuery(uniqueValue)
                    }

                    found shouldHaveSize 2
                    found.map { it.name } shouldContainExactlyInAnyOrder listOf("query_named_1", "query_named_2")
                }
            }

            context("Named Parameter (automatic extraction)") {
                it("binds parameters by Kotlin parameter name without @Param") {
                    val uniqueValue = valueCounter.incrementAndGet()
                    val uniqueName = "auto_param_$uniqueValue"

                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = uniqueName, value = uniqueValue))
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findByNameAndValueWithQuery(uniqueName, uniqueValue)
                    }

                    found.shouldNotBeNull()
                    found.name shouldBe uniqueName
                    found.value shouldBe uniqueValue
                }

                it("returns null when no entity matches") {
                    val found = tx.readOnly {
                        testEntityRepository.findByNameAndValueWithQuery("nonexistent_${System.nanoTime()}", 999999)
                    }

                    found.shouldBeNull()
                }
            }

            context("Positional Parameter") {
                it("binds parameters in ?1 and ?2 form") {
                    val baseValue = valueCounter.addAndGet(100)

                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "positional_1", value = baseValue + 10))
                        testEntityRepository.save(TestEntity(name = "positional_2", value = baseValue + 20))
                        testEntityRepository.save(TestEntity(name = "positional_3", value = baseValue + 30))
                        testEntityRepository.save(TestEntity(name = "positional_4", value = baseValue + 40))
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findByValueBetweenWithQuery(baseValue + 15, baseValue + 35)
                    }

                    found shouldHaveSize 2
                    found.map { it.value } shouldContainExactlyInAnyOrder listOf(baseValue + 20, baseValue + 30)
                }
            }

            context("@Modifying UPDATE") {
                it("executes an UPDATE query and returns the affected row count") {
                    val uniqueValue = valueCounter.incrementAndGet()
                    val newValue = uniqueValue + 50

                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "update_1", value = uniqueValue))
                        testEntityRepository.save(TestEntity(name = "update_2", value = uniqueValue))
                        testEntityRepository.save(TestEntity(name = "update_3", value = uniqueValue + 100))
                    }

                    val affected = tx.transactional {
                        testEntityRepository.updateValue(uniqueValue, newValue)
                    }

                    affected shouldBe 2

                    val updated = tx.readOnly {
                        testEntityRepository.findByValueWithQuery(newValue)
                    }
                    updated shouldHaveSize 2
                }

                it("completes a Unit-returning method without exposing the affected row count") {
                    val uniqueValue = valueCounter.incrementAndGet()
                    val newValue = uniqueValue + 50

                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "unit_update", value = uniqueValue))
                        testEntityRepository.updateValueWithoutCount(uniqueValue, newValue)
                    }

                    val updated = tx.readOnly {
                        testEntityRepository.findByValueWithQuery(newValue)
                    }
                    updated shouldHaveSize 1
                }

                it("clears stale entities from the transaction after a bulk update when clearAutomatically is enabled") {
                    tx.transactional {
                        val saved = testEntityRepository.save(TestEntity(name = "before_clear", value = 1))
                        val id = saved.id!!
                        testEntityRepository.findById(id)!!.name shouldBe "before_clear"

                        testEntityRepository.updateNameAndClear(id, "after_clear") shouldBe 1

                        testEntityRepository.findById(id)!!.name shouldBe "after_clear"
                    }
                }
            }

            context("@Modifying DELETE") {
                it("executes a DELETE query and returns the affected row count") {
                    val uniqueValue = valueCounter.incrementAndGet()

                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "delete_1", value = uniqueValue))
                        testEntityRepository.save(TestEntity(name = "delete_2", value = uniqueValue))
                        testEntityRepository.save(TestEntity(name = "delete_3", value = uniqueValue + 100))
                    }

                    val affected = tx.transactional {
                        testEntityRepository.deleteByValueWithQuery(uniqueValue)
                    }

                    affected shouldBe 2

                    val remaining = tx.readOnly {
                        testEntityRepository.findByValueWithQuery(uniqueValue)
                    }
                    remaining shouldHaveSize 0
                }
            }

            context("@Query + Page") {
                it("executes a query with pagination") {
                    val uniqueValue = valueCounter.incrementAndGet()

                    tx.transactional {
                        repeat(10) { i ->
                            testEntityRepository.save(TestEntity(name = "page_query_$i", value = uniqueValue))
                        }
                    }

                    val page = tx.readOnly {
                        testEntityRepository.findByValueWithQueryPageable(uniqueValue, PageRequest.of(0, 3))
                    }

                    page.content shouldHaveSize 3
                    page.totalElements shouldBe 10
                    page.totalPages shouldBe 4
                }
            }

            context("@Query + Slice") {
                it("reports whether a next page exists through Slice") {
                    val baseValue = valueCounter.addAndGet(100)

                    tx.transactional {
                        repeat(5) { i ->
                            testEntityRepository.save(TestEntity(name = "slice_query_$i", value = baseValue + i + 1))
                        }
                    }

                    val slice = tx.readOnly {
                        testEntityRepository.findByValueGreaterThanWithQuerySlice(baseValue, PageRequest.of(0, 3))
                    }

                    slice.content shouldHaveSize 3
                    slice.hasNext() shouldBe true
                }
            }

            context("@Query + explicit countQuery") {
                it("uses the explicitly specified countQuery") {
                    val uniqueValue = valueCounter.addAndGet(100)

                    tx.transactional {
                        repeat(7) { i ->
                            testEntityRepository.save(TestEntity(name = "explicit_count_$i", value = uniqueValue))
                        }
                    }

                    val page = tx.readOnly {
                        testEntityRepository.findByValueWithExplicitCount(uniqueValue, PageRequest.of(0, 5))
                    }

                    page.content shouldHaveSize 5
                    page.totalElements shouldBe 7
                }
            }
        }
    }
}
