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
 * Verifies behavior when Spring `@Transactional` and `tx.transactional {}` are combined.
 *
 * Failure to detect the outer Spring transaction would open a separate session and transaction,
 * causing rollback boundaries to diverge.
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
        describe("tx.transactional within @Transactional") {

            it("does not open a redundant session") {
                testService.opensRedundantSession() shouldBe false
            }

            it("joins and rolls back with the outer transaction") {
                shouldThrow<RuntimeException> {
                    testService.saveNestedAndFail("nested-rollback", 1)
                }

                tx.readOnly { testEntityRepository.count() } shouldBe 0L
            }

            it("cannot upgrade a read-only transaction to read-write") {
                shouldThrow<ReadOnlyTransactionException> {
                    testService.upgradeReadOnlyTransaction("should-not-persist")
                }

                tx.readOnly { testEntityRepository.count() } shouldBe 0L
            }
        }
    }
}
