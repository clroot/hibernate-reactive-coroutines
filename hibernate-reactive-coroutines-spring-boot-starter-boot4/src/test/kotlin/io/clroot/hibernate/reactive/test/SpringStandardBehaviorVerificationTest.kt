package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.service.RollbackTestService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.UnexpectedRollbackException

/**
 * Spring 표준 @Transactional 동작과의 일치성 검증 테스트.
 *
 * 이 테스트는 HibernateReactiveTransactionManager가 Spring의 표준 트랜잭션 동작과
 * 일치하게 동작하는지 검증합니다.
 *
 * ## Spring 표준 동작 참조
 * - Spring Framework Reference: Transaction Management
 * - https://docs.spring.io/spring-framework/reference/data-access/transaction.html
 *
 * ## 검증 항목
 * 1. REQUIRED 전파에서 참여 트랜잭션의 rollback-only 마킹
 * 2. RuntimeException 자동 롤백
 * 3. rollbackFor / noRollbackFor 설정
 * 4. readOnly 트랜잭션 제약
 */
@SpringBootTest(classes = [TestApplication::class, RollbackTestService::class])
class SpringStandardBehaviorVerificationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var rollbackService: RollbackTestService

    init {
        describe("Spring 표준 동작 검증: REQUIRED 전파에서 rollback-only") {
            /**
             * Spring 표준 동작:
             * - REQUIRED 전파에서 내부 트랜잭션이 기존 트랜잭션에 참여
             * - 내부에서 예외 발생 시 TransactionAspectSupport가 setRollbackOnly() 호출
             * - 외부에서 예외를 catch해도 트랜잭션은 이미 rollback-only 상태
             * - 커밋 시점에 UnexpectedRollbackException 발생
             *
             * 참조: AbstractPlatformTransactionManager.processCommit()
             * - defStatus.isGlobalRollbackOnly() || (isFailEarlyOnGlobalRollbackOnly() && defStatus.isLocalRollbackOnly())
             */
            it("내부 @Transactional 메서드에서 예외 발생 후 외부에서 catch하면 UnexpectedRollbackException이 발생해야 한다") {
                // Spring 표준: 참여 트랜잭션에서 예외 발생 시 rollback-only 마킹
                // 외부에서 catch해도 커밋 시 UnexpectedRollbackException 발생
                shouldThrow<UnexpectedRollbackException> {
                    rollbackService.outerCatchesInnerException(
                        "spring-standard-outer",
                        "spring-standard-inner",
                    )
                }

                // 전체 트랜잭션이 롤백되어 데이터가 없어야 함
                rollbackService.findByName("spring-standard-outer").shouldBeNull()
                rollbackService.findByName("spring-standard-inner").shouldBeNull()
            }

            it("내부 예외가 전파되면 외부도 함께 롤백된다") {
                // Spring 표준: REQUIRED에서 내부 예외 전파 시 같은 트랜잭션이므로 전체 롤백
                shouldThrow<RuntimeException> {
                    rollbackService.outerSaveAndCallInnerThatFails(
                        "propagate-outer",
                        "propagate-inner",
                    )
                }

                rollbackService.findByName("propagate-outer").shouldBeNull()
                rollbackService.findByName("propagate-inner").shouldBeNull()
            }
        }

        describe("Spring 표준 동작 검증: RuntimeException 자동 롤백") {
            /**
             * Spring 표준 동작:
             * - RuntimeException 및 Error는 기본적으로 롤백
             * - Checked Exception은 기본적으로 커밋 (하지만 Kotlin에서는 모든 예외가 unchecked)
             *
             * 참조: DefaultTransactionAttribute.rollbackOn(Throwable ex)
             * - return (ex instanceof RuntimeException || ex instanceof Error)
             */
            it("RuntimeException 발생 시 자동으로 롤백된다") {
                shouldThrow<RuntimeException> {
                    rollbackService.saveAndThrowRuntimeException("runtime-rollback-verify")
                }

                rollbackService.findByName("runtime-rollback-verify").shouldBeNull()
            }

            it("IllegalStateException (RuntimeException 하위) 발생 시 자동으로 롤백된다") {
                shouldThrow<IllegalStateException> {
                    rollbackService.saveAndThrowIllegalStateException("illegal-state-verify")
                }

                rollbackService.findByName("illegal-state-verify").shouldBeNull()
            }
        }

        describe("Spring 표준 동작 검증: rollbackFor / noRollbackFor") {
            /**
             * Spring 표준 동작:
             * - rollbackFor: 지정된 예외 타입에서 롤백
             * - noRollbackFor: 지정된 예외 타입에서 롤백하지 않음
             *
             * 참조: RuleBasedTransactionAttribute.rollbackOn(Throwable ex)
             */
            it("rollbackFor에 지정된 예외는 롤백된다") {
                shouldThrow<java.io.IOException> {
                    rollbackService.saveAndThrowCheckedWithRollbackFor("rollback-for-verify")
                }

                // rollbackFor=[IOException::class]로 지정되어 롤백됨
                rollbackService.findByName("rollback-for-verify").shouldBeNull()
            }

            it("noRollbackFor에 지정된 RuntimeException은 롤백되지 않는다") {
                shouldThrow<IllegalArgumentException> {
                    rollbackService.saveAndThrowNoRollbackForException("no-rollback-verify")
                }

                // noRollbackFor=[IllegalArgumentException::class]로 지정되어 커밋됨
                val found = rollbackService.findByName("no-rollback-verify")
                found.shouldNotBeNull()
            }
        }

        describe("Spring 표준 동작 검증: 정상 커밋") {
            /**
             * Spring 표준 동작:
             * - 예외 없이 메서드가 완료되면 트랜잭션 커밋
             */
            it("예외 없이 완료되면 커밋된다") {
                val entity = rollbackService.saveSuccessfully("commit-verify", 999)

                entity.id.shouldNotBeNull()
                rollbackService.findById(entity.id!!)?.name shouldBe "commit-verify"
            }
        }
    }
}
