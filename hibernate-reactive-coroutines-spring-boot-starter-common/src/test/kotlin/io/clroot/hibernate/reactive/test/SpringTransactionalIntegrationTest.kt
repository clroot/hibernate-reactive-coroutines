package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.service.TransactionalTestService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.smallrye.mutiny.coroutines.awaitSuspending
import org.hibernate.reactive.mutiny.Mutiny
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext

/**
 * Spring @Transactional and repository integration tests.
 *
 * Verifies that transaction sessions are shared when @Transactional is used with suspend functions.
 */
@SpringBootTest(classes = [TestApplication::class, TransactionalTestService::class])
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class SpringTransactionalIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var testService: TransactionalTestService

    @Autowired
    private lateinit var sessionFactory: Mutiny.SessionFactory

    init {
        describe("Spring @Transactional with suspend functions") {
            context("within a transaction context") {
                it("saves an entity through the repository") {
                    val result = testService.saveEntity("transactional-test", 100)

                    result.shouldNotBeNull()
                    result.id.shouldNotBeNull()
                    result.name shouldBe "transactional-test"
                }

                it("finds a saved entity") {
                    val saved = testService.saveEntity("findable", 200)

                    val found = testService.findById(saved.id!!)

                    found.shouldNotBeNull()
                    found.name shouldBe "findable"
                    found.value shouldBe 200
                }
            }

            context("transaction rollback") {
                it("rolls back changes when an exception is thrown") {
                    var savedId: Long? = null

                    shouldThrow<RuntimeException> {
                        testService.saveAndFail("will-rollback", 999) { id ->
                            savedId = id
                        }
                    }

                    savedId.shouldNotBeNull()

                    // Query in a new transaction to verify the rollback.
                    val found = testService.findById(savedId)
                    found.shouldBeNull()
                }
            }

            context("read-only transactions") {
                it("queries successfully in a read-only transaction") {
                    val saved = testService.saveEntity("readonly-test", 300)

                    val found = testService.findByIdReadOnly(saved.id!!)

                    found.shouldNotBeNull()
                    found.name shouldBe "readonly-test"
                }
            }

            context("automatic flush on transaction commit") {
                it("finds a saved entity from a new session after commit") {
                    val saved = testService.saveEntity("flush-test", 500)
                    saved.id.shouldNotBeNull()

                    // Use a new session to bypass the persistence-context cache.
                    val foundInDb = sessionFactory.withSession { session ->
                        session.find(TestEntity::class.java, saved.id)
                    }.awaitSuspending()

                    foundInDb.shouldNotBeNull()
                    foundInDb.name shouldBe "flush-test"
                    foundInDb.value shouldBe 500
                }

                it("flushes every saved entity when the transaction commits") {
                    val names = listOf("multi-flush-1", "multi-flush-2", "multi-flush-3")
                    val savedEntities = testService.saveMultipleEntities(names)

                    savedEntities shouldHaveSize 3
                    savedEntities.forEach { it.id.shouldNotBeNull() }

                    // Use a new session to bypass the persistence-context cache.
                    val foundInDb = sessionFactory.withSession { session ->
                        session.createQuery(
                            "FROM TestEntity WHERE name LIKE 'multi-flush-%' ORDER BY name",
                            TestEntity::class.java,
                        ).resultList
                    }.awaitSuspending()

                    foundInDb shouldHaveSize 3
                    foundInDb.map { it.name } shouldBe names
                }
            }

            context("batched write operations") {
                it("saves all entities in one transaction") {
                    val names = listOf("batch-1", "batch-2", "batch-3")

                    val saved = testService.saveMultipleEntities(names)

                    saved shouldHaveSize 3
                    saved.forEach { it.id.shouldNotBeNull() }
                    saved.map { it.name } shouldBe names

                    saved.forEach { entity ->
                        val found = testService.findById(entity.id!!)
                        found.shouldNotBeNull()
                        found.name shouldBe entity.name
                    }
                }

                it("rolls back every save when an exception is thrown") {
                    val names = listOf("rollback-1", "rollback-2", "rollback-3")
                    var savedIds: List<Long> = emptyList()

                    shouldThrow<RuntimeException> {
                        testService.saveMultipleAndFail(names) { ids ->
                            savedIds = ids
                        }
                    }

                    savedIds shouldHaveSize 3

                    // IDs may be assigned before the transaction is rolled back.
                    savedIds.forEach { id ->
                        val found = testService.findById(id)
                        found.shouldBeNull()
                    }
                }
            }
        }
    }
}
