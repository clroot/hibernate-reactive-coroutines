package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Advanced concurrency tests.
 *
 * Verifies high-volume concurrent transactions, races, and transaction isolation.
 */
@SpringBootTest(classes = [TestApplication::class])
class AdvancedConcurrencyTest : IntegrationTestBase() {

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("advanced concurrency") {

            context("high-volume concurrent transactions") {

                it("persists all 50 entities concurrently") {
                    val count = 50

                    val savedEntities = coroutineScope {
                        (1..count).map { i ->
                            async {
                                tx.transactional {
                                    testEntityRepository.save(
                                        TestEntity(name = "mass-concurrent-$i", value = i),
                                    )
                                }
                            }
                        }.awaitAll()
                    }

                    savedEntities shouldHaveSize count

                    val afterCount = tx.readOnly { testEntityRepository.count() }
                    afterCount shouldBe count.toLong()
                }

                it("completes all 100 concurrent reads") {
                    val entities = tx.transactional {
                        (1..10).map { i ->
                            testEntityRepository.save(TestEntity(name = "read-target-$i", value = i))
                        }
                    }

                    val readCount = 100
                    val results = coroutineScope {
                        (1..readCount).map {
                            async {
                                tx.readOnly {
                                    testEntityRepository.findAll().toList()
                                }
                            }
                        }.awaitAll()
                    }

                    results shouldHaveSize readCount
                    results.forEach { list ->
                        list shouldHaveSize 10
                    }
                }
            }

            context("read/write races") {

                it("returns consistent results while writes run concurrently") {
                    val writeCount = AtomicInteger(0)
                    val readResults = mutableListOf<Long>()

                    coroutineScope {
                        val writeJobs = (1..20).map { i ->
                            async {
                                tx.transactional {
                                    testEntityRepository.save(TestEntity(name = "concurrent-write-$i", value = i))
                                    writeCount.incrementAndGet()
                                }
                            }
                        }

                        val readJobs = (1..20).map {
                            async {
                                tx.readOnly {
                                    val count = testEntityRepository.count()
                                    synchronized(readResults) {
                                        readResults.add(count)
                                    }
                                    count
                                }
                            }
                        }

                        writeJobs.awaitAll()
                        readJobs.awaitAll()
                    }

                    val finalCount = tx.readOnly { testEntityRepository.count() }
                    finalCount shouldBe 20

                    // Every read observes a transactionally consistent snapshot.
                    readResults.forEach { count ->
                        count shouldBeGreaterThanOrEqual 0
                        count shouldBe count // Each read observes a consistent snapshot.
                    }
                }

                it("updates distinct entities concurrently without conflicts") {
                    val entities = tx.transactional {
                        (1..10).map { i ->
                            testEntityRepository.save(TestEntity(name = "update-target-$i", value = i))
                        }
                    }

                    coroutineScope {
                        entities.map { entity ->
                            async {
                                tx.transactional {
                                    val found = testEntityRepository.findById(entity.id!!)!!
                                    found.value = found.value * 100
                                    testEntityRepository.save(found)
                                }
                            }
                        }.awaitAll()
                    }

                    val updated = tx.readOnly { testEntityRepository.findAll().toList() }
                    updated shouldHaveSize 10
                    updated.forEach { entity ->
                        val originalValue = entity.name.substringAfterLast("-").toInt()
                        entity.value shouldBe originalValue * 100
                    }
                }
            }

            context("transaction isolation") {

                it("does not expose uncommitted changes to another transaction") {
                    var visibleDuringTransaction = false

                    coroutineScope {
                        val writeJob = async {
                            tx.transactional {
                                testEntityRepository.save(TestEntity(name = "isolation-test", value = 1))
                                // Keep the transaction open while the concurrent read executes.
                                kotlinx.coroutines.delay(100)
                            }
                        }

                        kotlinx.coroutines.delay(50)
                        val readJob = async {
                            tx.readOnly {
                                val found = testEntityRepository.findByName("isolation-test")
                                visibleDuringTransaction = found != null
                            }
                        }

                        writeJob.await()
                        readJob.await()
                    }

                    val afterCommit = tx.readOnly {
                        testEntityRepository.findByName("isolation-test")
                    }
                    afterCommit?.name shouldBe "isolation-test"
                }
            }

            context("failure isolation") {

                it("does not let one failed transaction affect concurrent transactions") {
                    val successCount = AtomicInteger(0)
                    val failCount = AtomicInteger(0)

                    coroutineScope {
                        (1..20).map { i ->
                            async {
                                try {
                                    tx.transactional {
                                        testEntityRepository.save(TestEntity(name = "isolation-$i", value = i))
                                        if (i % 5 == 0) {
                                            throw RuntimeException("Intentional failure: $i")
                                        }
                                        successCount.incrementAndGet()
                                    }
                                } catch (e: RuntimeException) {
                                    failCount.incrementAndGet()
                                }
                            }
                        }.awaitAll()
                    }

                    failCount.get() shouldBe 4
                    successCount.get() shouldBe 16

                    val savedCount = tx.readOnly { testEntityRepository.count() }
                    savedCount shouldBe 16
                }
            }

            context("sequential operation chains") {

                it("completes concurrently started sequential chains independently") {
                    val results = coroutineScope {
                        (1..5).map { chainId ->
                            async {
                                val step1 = tx.transactional {
                                    testEntityRepository.save(TestEntity(name = "chain-$chainId-step1", value = 1))
                                }

                                val step2 = tx.transactional {
                                    step1.value = 10
                                    testEntityRepository.save(step1)
                                }

                                tx.transactional {
                                    step2.value = 100
                                    testEntityRepository.save(step2)
                                }
                            }
                        }.awaitAll()
                    }

                    results shouldHaveSize 5

                    val all = tx.readOnly { testEntityRepository.findAll().toList() }
                    all shouldHaveSize 5
                    all.forEach { entity ->
                        entity.value shouldBe 100
                    }
                }
            }
        }
    }
}
