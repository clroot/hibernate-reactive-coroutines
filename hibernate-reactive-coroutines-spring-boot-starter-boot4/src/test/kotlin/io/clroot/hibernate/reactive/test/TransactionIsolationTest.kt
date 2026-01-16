package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.service.PropagationTestService
import io.kotest.matchers.nulls.shouldNotBeNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Spring @Transactional isolation 속성 테스트.
 *
 * @see <a href="https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html">
 *     Using @Transactional - isolation</a>
 *
 * ## Spring 표준 동작
 * - isolation 속성은 트랜잭션의 격리 수준을 지정
 * - 기본값은 Isolation.DEFAULT (데이터베이스 기본값 사용)
 * - 지원 레벨: READ_UNCOMMITTED, READ_COMMITTED, REPEATABLE_READ, SERIALIZABLE
 *
 * ## 현재 구현
 * - Hibernate Reactive는 데이터베이스의 기본 isolation을 사용
 * - isolation 설정이 예외 없이 적용되고 트랜잭션이 정상 동작하는지 검증
 */
@SpringBootTest(classes = [TestApplication::class, PropagationTestService::class])
class TransactionIsolationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var propagationService: PropagationTestService

    init {
        describe("Isolation.READ_COMMITTED") {
            it("READ_COMMITTED isolation으로 트랜잭션이 정상 동작한다") {
                val entity = propagationService.isolationReadCommitted("test")

                entity.id.shouldNotBeNull()
                propagationService.findByName("isolation-rc-test").shouldNotBeNull()
            }
        }

        describe("Isolation.REPEATABLE_READ") {
            it("REPEATABLE_READ isolation으로 트랜잭션이 정상 동작한다") {
                val entity = propagationService.isolationRepeatableRead("test")

                entity.id.shouldNotBeNull()
                propagationService.findByName("isolation-rr-test").shouldNotBeNull()
            }
        }

        describe("Isolation.SERIALIZABLE") {
            it("SERIALIZABLE isolation으로 트랜잭션이 정상 동작한다") {
                val entity = propagationService.isolationSerializable("test")

                entity.id.shouldNotBeNull()
                propagationService.findByName("isolation-s-test").shouldNotBeNull()
            }
        }
    }
}
