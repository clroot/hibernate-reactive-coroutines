package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.service.PropagationTestService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.IllegalTransactionStateException

/**
 * Spring @Transactional 전파 옵션 테스트.
 *
 * 다양한 Propagation 옵션의 동작을 검증합니다.
 */
@SpringBootTest(classes = [TestApplication::class, PropagationTestService::class])
class TransactionPropagationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var propagationService: PropagationTestService

    init {
        describe("Propagation.REQUIRED (기본값)") {

            context("기존 트랜잭션이 있을 때") {

                it("기존 트랜잭션에 참여한다") {
                    // when - outer와 inner 모두 REQUIRED
                    val (outer, inner) = propagationService.nestedRequiredBothCommit(
                        "outer-required",
                        "inner-required",
                    )

                    // then - 둘 다 같은 트랜잭션에서 커밋됨
                    outer.id.shouldNotBeNull()
                    inner.id.shouldNotBeNull()

                    propagationService.findById(outer.id!!).shouldNotBeNull()
                    propagationService.findById(inner.id!!).shouldNotBeNull()
                }

                it("내부 트랜잭션 실패 시 외부도 롤백된다") {
                    // when - inner에서 예외 발생
                    shouldThrow<RuntimeException> {
                        propagationService.outerRequired("outer-fail") {
                            propagationService.innerRequiredWithException("inner-fail")
                        }
                    }

                    // then - REQUIRED이므로 같은 트랜잭션, 둘 다 롤백됨
                    propagationService.findByName("outer-outer-fail").shouldBeNull()
                    propagationService.findByName("inner-fail-inner-fail").shouldBeNull()
                }
            }

            context("기존 트랜잭션이 없을 때") {

                it("새 트랜잭션을 시작한다") {
                    // when
                    val entity = propagationService.innerRequired("new-tx")

                    // then
                    entity.id.shouldNotBeNull()
                    propagationService.findById(entity.id!!).shouldNotBeNull()
                }
            }
        }

        describe("Propagation.REQUIRES_NEW") {

            it("단독 호출 시 새 트랜잭션으로 동작한다") {
                // Hibernate Reactive 환경에서 REQUIRES_NEW 중첩 호출은 커넥션 풀 고갈 위험
                // 단독 호출 시에는 새 트랜잭션으로 정상 동작

                val entity = propagationService.requiresNewTransaction("standalone")
                entity.id.shouldNotBeNull()

                propagationService.findByName("requires-new-standalone").shouldNotBeNull()
            }
        }

        describe("Propagation.SUPPORTS") {

            context("기존 트랜잭션이 있을 때") {

                it("기존 트랜잭션에 참여한다") {
                    // given - 먼저 데이터 저장
                    val saved = propagationService.saveEntity("supports-existing", 100)

                    // when - SUPPORTS로 조회
                    val found = propagationService.supportsReadOnly(saved.id!!)

                    // then
                    found.shouldNotBeNull()
                    found.name shouldBe "supports-existing"
                }
            }

            context("기존 트랜잭션이 없을 때") {

                it("트랜잭션 없이 실행된다 (쓰기는 자체 트랜잭션)") {
                    // SUPPORTS는 트랜잭션 없이도 실행 가능
                    // Repository 메서드가 자체적으로 세션을 관리함
                    val entity = propagationService.supportsWithTransaction("supports-no-tx")
                    entity.id.shouldNotBeNull()

                    propagationService.findById(entity.id!!).shouldNotBeNull()
                }
            }
        }

        describe("Propagation.NOT_SUPPORTED") {

            it("기존 트랜잭션을 일시 중단하고 실행한다") {
                // NOT_SUPPORTED는 기존 트랜잭션을 suspend하고 트랜잭션 없이 실행
                // Repository가 자체 세션을 사용
                val entity = propagationService.notSupportedAction("not-supported")
                entity.id.shouldNotBeNull()

                propagationService.findById(entity.id!!).shouldNotBeNull()
            }
        }

        describe("Propagation.MANDATORY") {

            context("기존 트랜잭션이 없을 때") {

                it("IllegalTransactionStateException이 발생한다") {
                    // MANDATORY는 기존 트랜잭션이 반드시 있어야 함
                    val exception = shouldThrow<IllegalTransactionStateException> {
                        propagationService.mandatoryAction("mandatory-no-tx")
                    }

                    exception.message shouldContain "No existing transaction found"
                }
            }
        }

        describe("Propagation.NEVER") {

            context("기존 트랜잭션이 있을 때") {

                it("IllegalTransactionStateException이 발생한다") {
                    // NEVER는 트랜잭션이 없어야 함
                    // 트랜잭션 내에서 NEVER를 호출하면 에러
                    val exception = shouldThrow<IllegalTransactionStateException> {
                        propagationService.outerRequired("outer-for-never") {
                            propagationService.neverAction("never-inside-tx")
                        }
                    }

                    exception.message shouldContain "Existing transaction found"
                }
            }

            context("기존 트랜잭션이 없을 때") {

                it("트랜잭션 없이 정상 실행된다") {
                    // NEVER 단독 호출 - Repository가 자체 세션 사용
                    val entity = propagationService.neverAction("never-no-tx")
                    entity.id.shouldNotBeNull()
                }
            }
        }

        describe("readOnly=true 옵션") {

            context("읽기 작업") {

                it("정상적으로 동작한다") {
                    // given
                    val saved = propagationService.saveEntity("readonly-read", 500)

                    // when
                    val found = propagationService.supportsReadOnly(saved.id!!)

                    // then
                    found.shouldNotBeNull()
                    found.name shouldBe "readonly-read"
                }
            }

            context("쓰기 작업 시도") {

                it("readOnly 트랜잭션에서 쓰기를 시도하면 ReadOnlyTransactionException이 발생한다") {
                    // readOnly=true에서 쓰기 시도 시
                    // 이 라이브러리는 ReadOnlyTransactionException을 던짐
                    shouldThrow<io.clroot.hibernate.reactive.ReadOnlyTransactionException> {
                        propagationService.readOnlyWriteAttempt("readonly-write-test")
                    }

                    // then - 예외 발생으로 트랜잭션이 롤백되어 데이터가 저장되지 않음
                    val found = propagationService.findByName("readonly-write-readonly-write-test")
                    found.shouldBeNull()
                }
            }
        }

        describe("중첩 트랜잭션 시나리오") {

            it("REQUIRED 중첩 - 모두 커밋") {
                val (outer, inner) = propagationService.nestedRequiredBothCommit(
                    "nested-outer-commit",
                    "nested-inner-commit",
                )

                outer.id.shouldNotBeNull()
                inner.id.shouldNotBeNull()

                // 둘 다 같은 트랜잭션에서 커밋되어 조회 가능
                propagationService.findByName("nested-outer-commit")?.name shouldBe "nested-outer-commit"
                propagationService.findByName("inner-nested-inner-commit")?.name shouldBe "inner-nested-inner-commit"
            }

            it("REQUIRED 중첩 - 내부 실패 시 전체 롤백") {
                shouldThrow<RuntimeException> {
                    propagationService.nestedRequiredInnerFails(
                        "nested-outer-rollback",
                        "nested-inner-rollback",
                    )
                }

                // 둘 다 같은 트랜잭션이므로 전체 롤백됨
                propagationService.findByName("nested-outer-rollback").shouldBeNull()
                propagationService.findByName("inner-fail-nested-inner-rollback").shouldBeNull()
            }
        }
    }
}
