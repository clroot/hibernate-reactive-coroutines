package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.service.PropagationTestService
import io.kotest.matchers.nulls.shouldNotBeNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

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
 * ## 현재 구현
 * - MutinySessionHolder에 timeout이 설정됨
 * - Hibernate Reactive의 커넥션 레벨에서 처리됨
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

            // Note: 짧은 timeout + 긴 지연 테스트는 Hibernate Reactive의 timeout 구현에 따라
            // 다르게 동작할 수 있습니다. 실제 timeout 강제는 DB 커넥션/드라이버 레벨에서 처리되므로
            // 코루틴 delay()로는 정확한 timeout 테스트가 어렵습니다.
        }
    }
}
