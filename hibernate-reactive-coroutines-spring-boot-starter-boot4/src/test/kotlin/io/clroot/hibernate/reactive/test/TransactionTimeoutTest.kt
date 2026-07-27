package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.service.PropagationTestService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.TransactionTimedOutException
import org.springframework.transaction.UnexpectedRollbackException

/**
 * Spring @Transactional timeout 속성 테스트.
 *
 * @see <a href="https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html">
 *     Using @Transactional - timeout</a>
 *
 * ## Spring 표준 동작
 * - timeout 속성은 트랜잭션이 완료되어야 하는 최대 시간(초)을 지정
 * - 기본값은 -1 (타임아웃 없음, 기반 트랜잭션 시스템의 기본값 사용)
 * - 타임아웃 초과 시 TransactionTimedOutException 발생
 *
 * 제한 시간을 넘긴 트랜잭션은 커밋되지 않고 Spring의 표준 timeout 예외를 발생시켜야 합니다.
 */
@SpringBootTest(classes = [TestApplication::class, PropagationTestService::class])
class TransactionTimeoutTest : IntegrationTestBase() {

    @Autowired
    private lateinit var propagationService: PropagationTestService

    init {
        describe("timeout 설정") {
            context("충분한 timeout 범위 내 작업") {
                it("timeout=10 설정 시 빠른 작업은 정상 완료된다") {
                    val entity = propagationService.transactionWithLongTimeout("timeout-ok")

                    entity.id.shouldNotBeNull()
                    propagationService.findByName("timeout-ok").shouldNotBeNull()
                }
            }

            context("timeout 초과") {
                it("timeout=1을 넘긴 트랜잭션은 롤백된다") {
                    val error = shouldThrow<UnexpectedRollbackException> {
                        propagationService.transactionWithShortTimeout("timeout-expired", 1_500)
                    }
                    error.mostSpecificCause.shouldBeInstanceOf<TransactionTimedOutException>()

                    propagationService.findByName("timeout-expired").shouldBeNull()
                }

                it("deadline 이후의 리포지토리 호출은 실행 전에 거부된다") {
                    shouldThrow<TransactionTimedOutException> {
                        propagationService.repositoryCallAfterTimeout("timeout-before-query", 1_100)
                    }

                    propagationService.findByName("timeout-before-query").shouldBeNull()
                }

                it("호출자가 timeout 예외를 잡아도 트랜잭션은 커밋되지 않는다") {
                    val error = shouldThrow<UnexpectedRollbackException> {
                        propagationService.catchRepositoryTimeout("timeout-caught", 1_100)
                    }
                    error.mostSpecificCause.shouldBeInstanceOf<TransactionTimedOutException>()

                    propagationService.findByName("timeout-caught").shouldBeNull()
                }
            }
        }
    }
}
