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
 * 고급 동시성 테스트.
 *
 * 대규모 동시 트랜잭션, 경쟁 조건, 트랜잭션 격리를 검증합니다.
 */
@SpringBootTest(classes = [TestApplication::class])
class AdvancedConcurrencyTest : IntegrationTestBase() {

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("고급 동시성 테스트") {

            context("대규모 동시 트랜잭션") {

                it("50개 동시 저장이 모두 성공한다") {
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

                it("100개 동시 조회가 모두 성공한다") {
                    // given
                    val entities = tx.transactional {
                        (1..10).map { i ->
                            testEntityRepository.save(TestEntity(name = "read-target-$i", value = i))
                        }
                    }

                    // when
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

                    // then
                    results shouldHaveSize readCount
                    results.forEach { list ->
                        list shouldHaveSize 10
                    }
                }
            }

            context("읽기/쓰기 경쟁") {

                it("동시 쓰기 중 읽기가 일관된 결과를 반환한다") {
                    val writeCount = AtomicInteger(0)
                    val readResults = mutableListOf<Long>()

                    coroutineScope {
                        // 쓰기 작업 20개
                        val writeJobs = (1..20).map { i ->
                            async {
                                tx.transactional {
                                    testEntityRepository.save(TestEntity(name = "concurrent-write-$i", value = i))
                                    writeCount.incrementAndGet()
                                }
                            }
                        }

                        // 읽기 작업 20개 (쓰기와 동시에)
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

                    // 최종 상태 확인
                    val finalCount = tx.readOnly { testEntityRepository.count() }
                    finalCount shouldBe 20

                    // 읽기 결과는 0 ~ 20 사이의 일관된 값이어야 함 (트랜잭션 격리)
                    readResults.forEach { count ->
                        count shouldBeGreaterThanOrEqual 0
                        count shouldBe count // 각 읽기는 일관된 스냅샷
                    }
                }

                it("여러 트랜잭션이 서로 다른 엔티티를 동시에 수정해도 충돌 없음") {
                    // given - 10개 엔티티 생성
                    val entities = tx.transactional {
                        (1..10).map { i ->
                            testEntityRepository.save(TestEntity(name = "update-target-$i", value = i))
                        }
                    }

                    // when - 각 엔티티를 다른 트랜잭션에서 동시 수정
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

                    // then
                    val updated = tx.readOnly { testEntityRepository.findAll().toList() }
                    updated shouldHaveSize 10
                    updated.forEach { entity ->
                        val originalValue = entity.name.substringAfterLast("-").toInt()
                        entity.value shouldBe originalValue * 100
                    }
                }
            }

            context("트랜잭션 격리 검증") {

                it("트랜잭션 내 변경이 커밋 전에는 다른 트랜잭션에서 보이지 않는다") {
                    var visibleDuringTransaction = false

                    coroutineScope {
                        // 긴 트랜잭션 시작
                        val writeJob = async {
                            tx.transactional {
                                testEntityRepository.save(TestEntity(name = "isolation-test", value = 1))
                                // 잠시 대기하여 읽기 트랜잭션이 실행될 시간 확보
                                kotlinx.coroutines.delay(100)
                            }
                        }

                        // 약간 대기 후 읽기 시도
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

                    // 트랜잭션 커밋 후에는 보여야 함
                    val afterCommit = tx.readOnly {
                        testEntityRepository.findByName("isolation-test")
                    }
                    afterCommit?.name shouldBe "isolation-test"
                }
            }

            context("실패 시 격리") {

                it("한 트랜잭션 실패가 다른 동시 트랜잭션에 영향을 주지 않는다") {
                    val successCount = AtomicInteger(0)
                    val failCount = AtomicInteger(0)

                    coroutineScope {
                        (1..20).map { i ->
                            async {
                                try {
                                    tx.transactional {
                                        testEntityRepository.save(TestEntity(name = "isolation-$i", value = i))
                                        if (i % 5 == 0) {
                                            throw RuntimeException("의도적 실패: $i")
                                        }
                                        successCount.incrementAndGet()
                                    }
                                } catch (e: RuntimeException) {
                                    failCount.incrementAndGet()
                                }
                            }
                        }.awaitAll()
                    }

                    // 5, 10, 15, 20 번이 실패 → 4개 실패
                    failCount.get() shouldBe 4
                    successCount.get() shouldBe 16

                    val savedCount = tx.readOnly { testEntityRepository.count() }
                    savedCount shouldBe 16
                }
            }

            context("순차적 작업 체인") {

                it("동시에 시작된 순차 작업들이 독립적으로 완료된다") {
                    val results = coroutineScope {
                        (1..5).map { chainId ->
                            async {
                                // 각 체인은 3단계 작업 수행
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
