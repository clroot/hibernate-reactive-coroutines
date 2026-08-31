package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.service.CustomCheckedException
import io.clroot.hibernate.reactive.test.service.CustomRuntimeException
import io.clroot.hibernate.reactive.test.service.RollbackTestService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.UnexpectedRollbackException
import java.io.IOException

/**
 * Spring @Transactional 롤백 규칙 테스트.
 *
 * @see <a href="https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html">
 *     Rolling Back a Declarative Transaction</a>
 *
 * ## Spring 표준 롤백 규칙
 * - RuntimeException 및 Error: 기본적으로 롤백
 * - Checked Exception: 기본적으로 롤백하지 않음 (커밋)
 * - rollbackFor: 지정된 예외에서 롤백
 * - noRollbackFor: 지정된 예외에서 롤백하지 않음
 */
@SpringBootTest(classes = [TestApplication::class, RollbackTestService::class])
class TransactionRollbackRulesTest : IntegrationTestBase() {

    @Autowired
    private lateinit var rollbackService: RollbackTestService

    init {
        describe("기본 롤백 동작 - RuntimeException") {

            it("RuntimeException 발생 시 자동으로 롤백된다") {
                // when
                shouldThrow<RuntimeException> {
                    rollbackService.saveAndThrowRuntimeException("runtime-rollback")
                }

                // then - 롤백되어 데이터가 없어야 함
                rollbackService.findByName("runtime-rollback").shouldBeNull()
            }

            it("IllegalStateException 발생 시 자동으로 롤백된다") {
                // when
                shouldThrow<IllegalStateException> {
                    rollbackService.saveAndThrowIllegalStateException("illegal-state-rollback")
                }

                // then
                rollbackService.findByName("illegal-state-rollback").shouldBeNull()
            }

            it("예외 없이 정상 완료되면 커밋된다") {
                // when
                val entity = rollbackService.saveSuccessfully("success-commit", 100)

                // then
                entity.id.shouldNotBeNull()
                val found = rollbackService.findById(entity.id!!)
                found.shouldNotBeNull()
                found.name shouldBe "success-commit"
            }
        }

        describe("Checked Exception 롤백 동작") {
            /**
             * Spring 표준: Checked Exception은 기본적으로 롤백하지 않음
             *
             * DefaultTransactionAttribute.rollbackOn(Throwable ex):
             *   return (ex instanceof RuntimeException || ex instanceof Error)
             *
             * IOException은 RuntimeException이 아니므로 롤백하지 않고 커밋됨.
             * Kotlin에서는 checked/unchecked 구분이 언어 레벨에서 없지만,
             * Spring은 JVM 바이트코드 레벨에서 예외 타입을 체크하므로 동일하게 동작함.
             */
            it("Checked Exception (IOException)은 기본적으로 롤백하지 않는다") {
                // when
                shouldThrow<IOException> {
                    rollbackService.saveAndThrowCheckedException("checked-no-rollback")
                }

                // then - Checked Exception이므로 커밋됨 (롤백 안 함)
                val found = rollbackService.findByName("checked-no-rollback")
                found.shouldNotBeNull()
                found.value shouldBe 3
            }
        }

        describe("rollbackFor 옵션") {

            it("rollbackFor에 지정된 Checked Exception은 롤백된다") {
                // when
                shouldThrow<IOException> {
                    rollbackService.saveAndThrowCheckedWithRollbackFor("rollback-for-checked")
                }

                // then - rollbackFor로 지정되어 롤백됨
                rollbackService.findByName("rollback-for-checked").shouldBeNull()
            }

            it("rollbackFor에 지정된 커스텀 예외도 롤백된다") {
                // when
                shouldThrow<CustomCheckedException> {
                    rollbackService.saveAndThrowCustomCheckedException("custom-checked-rollback")
                }

                // then
                rollbackService.findByName("custom-checked-rollback").shouldBeNull()
            }
        }

        describe("noRollbackFor 옵션") {

            it("noRollbackFor에 지정된 RuntimeException은 롤백되지 않는다") {
                // when
                shouldThrow<IllegalArgumentException> {
                    rollbackService.saveAndThrowNoRollbackForException("no-rollback-illegal-arg")
                }

                // then - noRollbackFor로 지정되어 커밋됨
                val found = rollbackService.findByName("no-rollback-illegal-arg")
                found.shouldNotBeNull()
                found.value shouldBe 6
            }

            it("noRollbackFor에 지정된 커스텀 예외도 롤백되지 않는다") {
                // when
                shouldThrow<CustomRuntimeException> {
                    rollbackService.saveAndThrowCustomNoRollbackException("custom-no-rollback")
                }

                // then - 커밋됨
                val found = rollbackService.findByName("custom-no-rollback")
                found.shouldNotBeNull()
                found.value shouldBe 7
            }
        }

        describe("중첩 트랜잭션에서의 롤백 전파") {

            it("내부 트랜잭션 실패 시 외부도 함께 롤백된다") {
                // REQUIRED 전파에서 내부 예외는 외부까지 전파됨
                shouldThrow<RuntimeException> {
                    rollbackService.outerSaveAndCallInnerThatFails(
                        "outer-propagate",
                        "inner-propagate",
                    )
                }

                // 둘 다 같은 트랜잭션이므로 전체 롤백
                rollbackService.findByName("outer-propagate").shouldBeNull()
                rollbackService.findByName("inner-propagate").shouldBeNull()
            }

            it("내부 트랜잭션이 rollback-only로 마킹되면 외부에서 예외를 catch해도 전체 롤백된다") {
                // REQUIRED 전파에서 내부 트랜잭션이 예외를 던지면 트랜잭션이 rollback-only로 마킹됨
                // 외부에서 예외를 catch해도 커밋 시점에 UnexpectedRollbackException 발생
                shouldThrow<UnexpectedRollbackException> {
                    rollbackService.outerCatchesInnerException(
                        "outer-catch",
                        "inner-caught",
                    )
                }

                // then - 두 엔티티 모두 롤백되었는지 확인
                rollbackService.findByName("outer-catch").shouldBeNull()
                rollbackService.findByName("inner-caught").shouldBeNull()
            }
        }

        describe("여러 엔티티 저장 시 롤백") {

            it("여러 엔티티를 정상적으로 저장하고 커밋한다") {
                val names = listOf("multi-1", "multi-2", "multi-3")

                // 정상 저장
                val saved = rollbackService.saveMultipleSuccessfully(names)
                saved.size shouldBe 3
                saved.forEach { it.id.shouldNotBeNull() }

                // 저장된 엔티티 확인
                saved.forEach { entity ->
                    rollbackService.findById(entity.id!!).shouldNotBeNull()
                }
            }
        }

        describe("롤백 후 데이터 일관성") {

            it("롤백 후 이전 상태가 유지된다") {
                // given - 먼저 데이터 저장
                val existing = rollbackService.saveSuccessfully("existing-before-rollback", 999)
                existing.id.shouldNotBeNull()

                // when - 새 저장 시도 중 예외 발생
                shouldThrow<RuntimeException> {
                    rollbackService.saveAndThrowRuntimeException("should-be-rolled-back")
                }

                // then - 롤백된 데이터는 없고, 기존 데이터는 유지
                rollbackService.findByName("should-be-rolled-back").shouldBeNull()
                rollbackService.findById(existing.id!!)?.name shouldBe "existing-before-rollback"
            }
        }
    }
}
