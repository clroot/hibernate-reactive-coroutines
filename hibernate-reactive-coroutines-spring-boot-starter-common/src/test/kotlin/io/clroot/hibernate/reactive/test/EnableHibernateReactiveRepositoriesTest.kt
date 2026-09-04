package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.test.entity.AnotherEntity
import io.clroot.hibernate.reactive.test.repository.AnotherEntityRepository
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

/**
 * Tests the @EnableHibernateReactiveRepositories annotation.
 *
 * Verifies repository bean registration and CRUD operations.
 */
@SpringBootTest(classes = [TestApplication::class])
class EnableHibernateReactiveRepositoriesTest : IntegrationTestBase() {

    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var anotherEntityRepository: AnotherEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("@EnableHibernateReactiveRepositories") {
            context("repository scanning") {
                it("registers repositories in the configured package as beans") {
                    val bean = context.getBean(AnotherEntityRepository::class.java)
                    bean.shouldNotBeNull()
                }

                it("supports repository CRUD operations") {
                    val entity = AnotherEntity(description = "test description")

                    val saved = tx.transactional {
                        anotherEntityRepository.save(entity)
                    }

                    saved.id.shouldNotBeNull()
                    saved.description shouldBe "test description"

                    val found = tx.readOnly {
                        anotherEntityRepository.findById(saved.id!!)
                    }

                    found.shouldNotBeNull()
                    found.description shouldBe "test description"
                }
            }
        }
    }
}
