package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.ReadOnlyTransactionException
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.clroot.hibernate.reactive.test.service.TransactionalTestService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext

/**
 * Spring `@Transactional`과 `tx.transactional {}`을 함께 쓸 때의 동작을 검증합니다.
 *
 * `tx.transactional`이 바깥 Spring 트랜잭션을 감지하지 못하면 별도 세션과 트랜잭션이 열려
 * 롤백 경계가 어긋납니다.
 */
@SpringBootTest(classes = [TestApplication::class, TransactionalTestService::class])
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class MixedTransactionModelTest : IntegrationTestBase() {

    @Autowired
    private lateinit var testService: TransactionalTestService

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    init {
        describe("@Transactional 안의 tx.transactional") {

            it("중복 세션을 열지 않는다") {
                testService.opensRedundantSession() shouldBe false
            }

            it("바깥 트랜잭션에 참여하여 함께 롤백된다") {
                shouldThrow<RuntimeException> {
                    testService.saveNestedAndFail("nested-rollback", 1)
                }

                tx.readOnly { testEntityRepository.count() } shouldBe 0L
            }

            it("읽기 전용 트랜잭션을 쓰기로 승격하지 못한다") {
                shouldThrow<ReadOnlyTransactionException> {
                    testService.upgradeReadOnlyTransaction("should-not-persist")
                }

                tx.readOnly { testEntityRepository.count() } shouldBe 0L
            }
        }
    }
}
