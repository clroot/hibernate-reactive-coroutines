package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.test.benchmark.BenchmarkRunner
import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.doubles.shouldBeGreaterThan
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest

/**
 * Performance benchmark tests.
 *
 * Measures baseline library performance.
 * `@Tag("benchmark")` excludes these tests from the regular suite.
 *
 * Run with `./gradlew :hibernate-reactive-coroutines-spring-boot-starter:benchmark` or
 * `:hibernate-reactive-coroutines-spring-boot-starter-boot4:benchmark`.
 */
@Tag("benchmark")
@SpringBootTest(classes = [TestApplication::class])
class PerformanceBenchmarkTest : IntegrationTestBase() {

    @Autowired
    private lateinit var repository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    private val runner = BenchmarkRunner(
        warmupIterations = 5,
        measureIterations = 50,
    )

    init {
        describe("Performance benchmarks") {

            context("single-entity CRUD") {

                it("measures create latency") {
                    var counter = 0
                    val result = runner.benchmark("Single Create") {
                        tx.transactional {
                            repository.save(
                                TestEntity(
                                    name = "bench-create-${++counter}",
                                    value = counter,
                                ),
                            )
                        }
                    }
                    result.printReport()

                    // Regression threshold: P95 must remain below 100 ms.
                    result.p95Ms shouldBeLessThan 100
                }

                it("measures read latency") {
                    val entity = tx.transactional {
                        repository.save(TestEntity(name = "bench-read", value = 1))
                    }

                    val result = runner.benchmark("Single Read") {
                        tx.readOnly {
                            repository.findByName("bench-read")
                        }
                    }
                    result.printReport()

                    // Regression threshold: P95 must remain below 50 ms.
                    result.p95Ms shouldBeLessThan 50
                }

                it("measures update latency") {
                    val entity = tx.transactional {
                        repository.save(TestEntity(name = "bench-update", value = 1))
                    }

                    var counter = 0
                    val result = runner.benchmark("Single Update") {
                        tx.transactional {
                            val found = repository.findById(entity.id!!)!!
                            found.value = ++counter
                            repository.save(found)
                        }
                    }
                    result.printReport()

                    // Regression threshold: P95 must remain below 100 ms.
                    result.p95Ms shouldBeLessThan 100
                }

                it("measures delete latency") {
                    var counter = 0
                    val result = runner.benchmark(
                        name = "Single Delete",
                        setup = {
                            tx.transactional {
                                repository.save(TestEntity(name = "bench-delete-${++counter}", value = 1))
                            }
                        },
                    ) {
                        tx.transactional {
                            repository.deleteByName("bench-delete-$counter")
                        }
                    }
                    result.printReport()

                    // Regression threshold: P95 must remain below 100 ms.
                    result.p95Ms shouldBeLessThan 100
                }
            }

            context("batch operations") {

                it("measures saving a batch of 100 entities") {
                    var batchCounter = 0
                    val result = runner.benchmark(
                        name = "Batch Save 100",
                        teardown = {
                            tx.transactional {
                                repository.deleteAll()
                            }
                        },
                    ) {
                        tx.transactional {
                            val batch = ++batchCounter
                            (1..100).map { i ->
                                repository.save(
                                    TestEntity(
                                        name = "batch-$batch-$i",
                                        value = i,
                                    ),
                                )
                            }
                        }
                    }
                    result.printReport()

                    // Regression threshold: average time must remain below 500 ms.
                    result.avgTimeMs shouldBeLessThan 500.0
                }
            }

            context("paginated reads") {

                beforeEach {
                    tx.transactional {
                        (1..1000).map { i ->
                            repository.save(TestEntity(name = "paging-$i", value = i % 10))
                        }
                    }
                }

                it("measures paginated reads across 1,000 entities") {
                    val result = runner.benchmark("Paging Query") {
                        tx.readOnly {
                            repository.findAll(PageRequest.of(50, 10))
                        }
                    }
                    result.printReport()

                    // Regression threshold: P95 must remain below 100 ms.
                    result.p95Ms shouldBeLessThan 100
                }
            }

            context("concurrency") {

                it("measures throughput for 10 concurrent transactions") {
                    val result = runner.benchmarkConcurrent(
                        name = "Concurrent 10 Transactions",
                        concurrency = 10,
                        iterationsPerCoroutine = 20,
                    ) {
                        tx.transactional {
                            repository.save(
                                TestEntity(
                                    name = "concurrent-${System.nanoTime()}",
                                    value = 1,
                                ),
                            )
                        }
                    }
                    result.printReport()

                    // Regression threshold: throughput must remain above 50 ops/sec.
                    result.throughput shouldBeGreaterThan 50.0
                }
            }
        }
    }
}
