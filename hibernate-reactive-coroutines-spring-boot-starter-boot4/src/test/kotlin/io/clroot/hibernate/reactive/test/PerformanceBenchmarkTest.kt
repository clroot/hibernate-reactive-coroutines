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
 * 성능 벤치마크 테스트.
 *
 * 라이브러리의 기본 성능을 측정하고 기준선을 검증합니다.
 * `@Tag("benchmark")`로 표시되어 일반 테스트에서 제외됩니다.
 *
 * 실행: `./gradlew :hibernate-reactive-coroutines-spring-boot-starter-boot4:benchmark`
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
        describe("성능 벤치마크") {

            context("단일 엔티티 CRUD") {

                it("Create 레이턴시") {
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

                    // 기준선: P95 < 100ms
                    result.p95Ms shouldBeLessThan 100
                }

                it("Read 레이턴시") {
                    // Setup: 조회할 엔티티 생성
                    val entity = tx.transactional {
                        repository.save(TestEntity(name = "bench-read", value = 1))
                    }

                    val result = runner.benchmark("Single Read") {
                        tx.readOnly {
                            repository.findByName("bench-read")
                        }
                    }
                    result.printReport()

                    // 기준선: P95 < 50ms
                    result.p95Ms shouldBeLessThan 50
                }

                it("Update 레이턴시") {
                    // Setup
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

                    // 기준선: P95 < 100ms
                    result.p95Ms shouldBeLessThan 100
                }

                it("Delete 레이턴시") {
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

                    // 기준선: P95 < 100ms
                    result.p95Ms shouldBeLessThan 100
                }
            }

            context("배치 작업") {

                it("100개 엔티티 배치 저장") {
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

                    // 기준선: 평균 < 500ms
                    result.avgTimeMs shouldBeLessThan 500.0
                }
            }

            context("페이징 조회") {

                beforeEach {
                    // 1000개 테스트 데이터 생성
                    tx.transactional {
                        (1..1000).map { i ->
                            repository.save(TestEntity(name = "paging-$i", value = i % 10))
                        }
                    }
                }

                it("1000개 엔티티 페이징 조회") {
                    val result = runner.benchmark("Paging Query") {
                        tx.readOnly {
                            repository.findAll(PageRequest.of(50, 10)) // 중간 페이지
                        }
                    }
                    result.printReport()

                    // 기준선: P95 < 100ms
                    result.p95Ms shouldBeLessThan 100
                }
            }

            context("동시성") {

                it("동시 10개 트랜잭션 처리량") {
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

                    // 기준선: 최소 50 ops/sec
                    result.throughput shouldBeGreaterThan 50.0
                }
            }
        }
    }
}
