package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.service.PropagationTestService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Tests Spring @Transactional isolation settings.
 *
 * @see <a href="https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html">
 *     Using @Transactional - isolation</a>
 *
 * ## Spring behavior
 * - The isolation setting specifies the transaction isolation level.
 * - The default is Isolation.DEFAULT, which uses the database default.
 * - Supported levels are READ_UNCOMMITTED, READ_COMMITTED, REPEATABLE_READ, and SERIALIZABLE.
 *
 * Queries PostgreSQL's effective isolation level within each transaction to verify that the declared level is applied.
 */
@SpringBootTest(classes = [TestApplication::class, PropagationTestService::class])
class TransactionIsolationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var propagationService: PropagationTestService

    init {
        describe("Isolation.READ_COMMITTED") {
            it("runs the transaction at READ_COMMITTED isolation") {
                val (entity, isolation) = propagationService.isolationReadCommitted("test")

                entity.id.shouldNotBeNull()
                isolation shouldBe "read committed"
                propagationService.findByName("isolation-rc-test").shouldNotBeNull()
            }
        }

        describe("Isolation.REPEATABLE_READ") {
            it("runs the transaction at REPEATABLE_READ isolation") {
                val (entity, isolation) = propagationService.isolationRepeatableRead("test")

                entity.id.shouldNotBeNull()
                isolation shouldBe "repeatable read"
                propagationService.findByName("isolation-rr-test").shouldNotBeNull()
            }
        }

        describe("Isolation.SERIALIZABLE") {
            it("runs the transaction at SERIALIZABLE isolation") {
                val (entity, isolation) = propagationService.isolationSerializable("test")

                entity.id.shouldNotBeNull()
                isolation shouldBe "serializable"
                propagationService.findByName("isolation-s-test").shouldNotBeNull()
            }
        }
    }
}
