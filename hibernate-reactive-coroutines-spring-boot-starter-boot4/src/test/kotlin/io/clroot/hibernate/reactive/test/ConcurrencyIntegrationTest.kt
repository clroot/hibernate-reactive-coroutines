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
        describe("동시성 테스트") {

            context("여러 코루틴이 동시에 save를 호출할 때") {

                it("모든 엔티티가 올바르게 저장된다") {
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

            context("여러 코루틴이 동시에 findById를 호출할 때") {

                it("각 코루틴이 독립적으로 조회한다") {
                    // given
                    val saved = tx.transactional {
                        testEntityRepository.save(TestEntity(name = "concurrent-find", value = 100))
                    }

                    // when
                    val results = coroutineScope {
                        (1..10).map {
                            async {
                                tx.readOnly {
                                    testEntityRepository.findById(saved.id!!)
                                }
                            }
                        }.awaitAll()
                    }

                    // then
                    results.filterNotNull() shouldHaveSize 10
                    results.forEach { it?.name shouldBe "concurrent-find" }
                }
            }

            context("여러 코루틴이 동시에 다른 엔티티를 업데이트할 때") {

                it("각 업데이트가 독립적으로 수행된다") {
                    // given
                    val entities = tx.transactional {
                        (1..5).map { i ->
                            testEntityRepository.save(TestEntity(name = "update-target-$i", value = i))
                        }
                    }

                    // when
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

                    // then
                    val updated = tx.readOnly {
                        entities.mapNotNull { testEntityRepository.findById(it.id!!) }
                    }

                    updated shouldHaveSize 5
                    updated.forEach { it.value shouldBe (it.name.substringAfterLast("-").toInt() * 10) }
                }
            }

            context("여러 코루틴이 동시에 읽기/쓰기 작업을 혼합할 때") {

                it("트랜잭션 격리가 유지된다") {
                    coroutineScope {
                        // 쓰기 작업들
                        val writeJobs = (1..5).map { i ->
                            async {
                                tx.transactional {
                                    testEntityRepository.save(TestEntity(name = "mixed-write-$i", value = i))
                                }
                            }
                        }

                        // 읽기 작업들
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
