package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

class HibernateReactiveRepositoryFactoryBeanTest : DescribeSpec({

    val sessionProvider = mockk<TransactionalAwareSessionProvider>()
    val transactionExecutor = mockk<ReactiveTransactionExecutor>()

    describe("HibernateReactiveRepositoryFactoryBean") {

        context("getObject") {

            it("Repository 인터페이스의 프록시를 생성한다") {
                val factoryBean = HibernateReactiveRepositoryFactoryBean(TestUserRepository::class.java)
                factoryBean.sessionProvider = sessionProvider
                factoryBean.transactionExecutor = transactionExecutor

                val proxy = factoryBean.getObject()

                proxy.shouldBeInstanceOf<TestUserRepository>()
            }

            it("제네릭 타입 파라미터가 있는 Repository도 처리한다") {
                val factoryBean = HibernateReactiveRepositoryFactoryBean(TestOrderRepository::class.java)
                factoryBean.sessionProvider = sessionProvider
                factoryBean.transactionExecutor = transactionExecutor

                val proxy = factoryBean.getObject()

                proxy.shouldBeInstanceOf<TestOrderRepository>()
            }
        }

        context("getObjectType") {

            it("Repository 인터페이스 클래스를 반환한다") {
                val factoryBean = HibernateReactiveRepositoryFactoryBean(TestUserRepository::class.java)

                factoryBean.objectType shouldBe TestUserRepository::class.java
            }
        }

        context("isSingleton") {

            it("true를 반환한다") {
                val factoryBean = HibernateReactiveRepositoryFactoryBean(TestUserRepository::class.java)

                factoryBean.isSingleton shouldBe true
            }
        }

        context("extractGenericTypes 예외 처리") {

            it("CoroutineCrudRepository를 상속하지 않는 인터페이스는 예외를 던진다") {
                val factoryBean = HibernateReactiveRepositoryFactoryBean(
                    @Suppress("UNCHECKED_CAST")
                    (InvalidRepository::class.java as Class<CoroutineCrudRepository<*, *>>),
                )
                factoryBean.sessionProvider = sessionProvider
                factoryBean.transactionExecutor = transactionExecutor

                shouldThrow<IllegalArgumentException> {
                    factoryBean.getObject()
                }
            }
        }
    }
}) {
    companion object {
        // 테스트용 엔티티
        class User(val id: Long, val name: String)
        class Order(val id: String, val amount: Int)

        // 테스트용 Repository
        interface TestUserRepository : CoroutineCrudRepository<User, Long>
        interface TestOrderRepository : CoroutineCrudRepository<Order, String>

        // 잘못된 Repository (CoroutineCrudRepository를 상속하지 않음)
        interface InvalidRepository
    }
}
