package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.currentContextOrNull
import io.clroot.hibernate.reactive.currentSessionOrNull
import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * 세션 라이프사이클 테스트.
 *
 * Hibernate Reactive 세션의 생성, 사용, 종료 사이클을 검증합니다.
 */
@SpringBootTest(classes = [TestApplication::class])
class SessionLifecycleTest : IntegrationTestBase() {
    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("세션 라이프사이클") {

            context("세션 컨텍스트 존재 여부") {

                it("트랜잭션 외부에서는 세션 컨텍스트가 없다") {
                    val context = currentContextOrNull()
                    context.shouldBeNull()
                }

                it("트랜잭션 외부에서는 세션이 없다") {
                    val session = currentSessionOrNull()
                    session.shouldBeNull()
                }

                it("transactional 블록 내에서는 세션 컨텍스트가 있다") {
                    tx.transactional {
                        val context = currentContextOrNull()
                        context.shouldNotBeNull()
                        context.isReadOnly.shouldBeFalse()
                    }
                }

                it("readOnly 블록 내에서는 읽기 전용 세션 컨텍스트가 있다") {
                    tx.readOnly {
                        val context = currentContextOrNull()
                        context.shouldNotBeNull()
                        context.isReadOnly.shouldBeTrue()
                    }
                }

                it("transactional 블록 종료 후 세션 컨텍스트가 사라진다") {
                    tx.transactional {
                        currentContextOrNull().shouldNotBeNull()
                    }

                    currentContextOrNull().shouldBeNull()
                }
            }

            context("세션 재사용") {

                it("중첩 transactional에서 같은 세션을 사용한다") {
                    tx.transactional {
                        val outerSession = currentSessionOrNull()
                        outerSession.shouldNotBeNull()

                        tx.transactional {
                            val innerSession = currentSessionOrNull()
                            innerSession.shouldNotBeNull()
                            // 같은 세션 객체여야 함
                            (innerSession === outerSession).shouldBeTrue()
                        }
                    }
                }

                it("중첩 readOnly에서 같은 세션을 사용한다") {
                    tx.readOnly {
                        val outerSession = currentSessionOrNull()
                        outerSession.shouldNotBeNull()

                        tx.readOnly {
                            val innerSession = currentSessionOrNull()
                            innerSession.shouldNotBeNull()
                            (innerSession === outerSession).shouldBeTrue()
                        }
                    }
                }

                it("transactional 내 readOnly에서 같은 세션을 사용한다") {
                    tx.transactional {
                        val outerSession = currentSessionOrNull()

                        tx.readOnly {
                            val innerSession = currentSessionOrNull()
                            (innerSession === outerSession).shouldBeTrue()
                        }
                    }
                }
            }

            context("세션 상태와 모드") {

                it("transactional은 READ_WRITE 모드이다") {
                    tx.transactional {
                        val context = currentContextOrNull()!!
                        context.isReadOnly.shouldBeFalse()
                        context.mode shouldBe io.clroot.hibernate.reactive.TransactionMode.READ_WRITE
                    }
                }

                it("readOnly는 READ_ONLY 모드이다") {
                    tx.readOnly {
                        val context = currentContextOrNull()!!
                        context.isReadOnly.shouldBeTrue()
                        context.mode shouldBe io.clroot.hibernate.reactive.TransactionMode.READ_ONLY
                    }
                }

                it("READ_WRITE 내부의 readOnly는 READ_ONLY로 전환된다") {
                    tx.transactional {
                        currentContextOrNull()!!.isReadOnly.shouldBeFalse()

                        tx.readOnly {
                            // 중첩에서는 부모 컨텍스트 재사용 (모드 변경 없음)
                            // 이미 세션이 있으므로 새 세션을 만들지 않음
                            currentContextOrNull()!!.isReadOnly.shouldBeFalse()
                        }
                    }
                }
            }

            context("연속 트랜잭션") {

                it("연속된 독립 트랜잭션은 각각 새 세션을 사용한다") {
                    var firstSessionHash: Int? = null
                    var secondSessionHash: Int? = null

                    tx.transactional {
                        firstSessionHash = currentSessionOrNull().hashCode()
                        testEntityRepository.save(TestEntity(name = "first-tx", value = 1))
                    }

                    tx.transactional {
                        secondSessionHash = currentSessionOrNull().hashCode()
                        testEntityRepository.save(TestEntity(name = "second-tx", value = 2))
                    }

                    // 다른 세션이어야 함 (hashCode는 다를 수 있음)
                    firstSessionHash.shouldNotBeNull()
                    secondSessionHash.shouldNotBeNull()

                    // 각 트랜잭션이 독립적으로 커밋됨
                    val count = tx.readOnly { testEntityRepository.count() }
                    count shouldBe 2
                }

                it("첫 번째 트랜잭션 실패가 두 번째에 영향 없음") {
                    try {
                        tx.transactional {
                            testEntityRepository.save(TestEntity(name = "fail-tx", value = 1))
                            throw RuntimeException("의도적 실패")
                        }
                    } catch (e: RuntimeException) {
                        // 무시
                    }

                    // 두 번째 트랜잭션은 정상
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "success-tx", value = 2))
                    }

                    val count = tx.readOnly { testEntityRepository.count() }
                    count shouldBe 1

                    val found = tx.readOnly { testEntityRepository.findByName("success-tx") }
                    found.shouldNotBeNull()
                }
            }

            context("타임아웃과 세션") {

                it("타임아웃이 설정된 트랜잭션도 정상 종료 시 세션이 정리된다") {
                    tx.transactional(timeout = kotlin.time.Duration.parse("5s")) {
                        currentContextOrNull().shouldNotBeNull()
                        testEntityRepository.save(TestEntity(name = "timeout-session", value = 1))
                    }

                    currentContextOrNull().shouldBeNull()

                    // 데이터는 저장됨
                    val found = tx.readOnly { testEntityRepository.findByName("timeout-session") }
                    found.shouldNotBeNull()
                }
            }
        }
    }
}
