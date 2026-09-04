package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(classes = [TestApplication::class])
class ConcurrencyIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("concurrency") {

            context("when multiple coroutines call save concurrently") {

                it("persists every entity correctly") {
                    val count = 10

                    val savedEntities = coroutineScope {
                        (1..count).map { i ->
                            async {
                                tx.transactional {
                                    testEntityRepository.save(
                                        TestEntity(name = "concurrent-$i", value = i),
                                    )
                                }
                            }
                        }.awaitAll()
                    }

                    savedEntities shouldHaveSize count
                    savedEntities.map { it.name }.toSet() shouldHaveSize count

                    val afterCount = tx.readOnly { testEntityRepository.count() }
                    afterCount shouldBe count.toLong()
                }
            }

            context("when multiple coroutines call findById concurrently") {

                it("executes each lookup independently") {
                    val saved = tx.transactional {
                        testEntityRepository.save(TestEntity(name = "concurrent-find", value = 100))
                    }

                    val results = coroutineScope {
                        (1..10).map {
                            async {
                                tx.readOnly {
                                    testEntityRepository.findById(saved.id!!)
                                }
                            }
                        }.awaitAll()
                    }

                    results.filterNotNull() shouldHaveSize 10
                    results.forEach { it?.name shouldBe "concurrent-find" }
                }
            }

            context("when multiple coroutines update different entities concurrently") {

                it("executes each update independently") {
                    val entities = tx.transactional {
                        (1..5).map { i ->
                            testEntityRepository.save(TestEntity(name = "update-target-$i", value = i))
                        }
                    }

                    coroutineScope {
                        entities.map { entity ->
                            async {
                                tx.transactional {
                                    entity.value = entity.value * 10
                                    testEntityRepository.save(entity)
                                }
                            }
                        }.awaitAll()
                    }

                    val updated = tx.readOnly {
                        entities.mapNotNull { testEntityRepository.findById(it.id!!) }
                    }

                    updated shouldHaveSize 5
                    updated.forEach { it.value shouldBe (it.name.substringAfterLast("-").toInt() * 10) }
                }
            }

            context("when multiple coroutines mix reads and writes") {

                it("maintains transaction isolation") {
                    coroutineScope {
                        val writeJobs = (1..5).map { i ->
                            async {
                                tx.transactional {
                                    testEntityRepository.save(TestEntity(name = "mixed-write-$i", value = i))
                                }
                            }
                        }

                        val readJobs = (1..5).map {
                            async {
                                tx.readOnly {
                                    testEntityRepository.findAll()
                                }
                            }
                        }

                        writeJobs.awaitAll()
                        readJobs.awaitAll()
                    }

                    val afterCount = tx.readOnly { testEntityRepository.count() }
                    afterCount shouldBe 5
                }
            }
        }
    }
}
