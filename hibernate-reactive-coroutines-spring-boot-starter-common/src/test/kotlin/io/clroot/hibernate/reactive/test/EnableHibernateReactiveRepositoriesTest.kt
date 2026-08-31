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
 * @EnableHibernateReactiveRepositories 어노테이션 테스트
 *
 * Repository가 빈으로 등록되고 CRUD가 정상 동작하는지 확인합니다.
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
            context("Repository 스캔") {
                it("지정된 패키지의 Repository가 빈으로 등록된다") {
                    val bean = context.getBean(AnotherEntityRepository::class.java)
                    bean.shouldNotBeNull()
                }

                it("Repository CRUD가 정상 동작한다") {
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
