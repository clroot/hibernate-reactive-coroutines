package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import jakarta.persistence.metamodel.EntityType
import jakarta.persistence.metamodel.Metamodel
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

class HibernateReactiveRepositoryFactoryBeanTest : DescribeSpec({

    val sessionProvider = mockk<TransactionalAwareSessionProvider>()
    val transactionExecutor = mockk<ReactiveTransactionExecutor>()

    // HQL entity names come from the JPA metamodel, so mock every test class as a registered entity.
    val metamodel = mockk<Metamodel>()
    every { sessionProvider.metamodel } returns metamodel
    every { metamodel.entity(any<Class<*>>()) } answers {
        mockk<EntityType<*>> {
            every { name } returns firstArg<Class<*>>().simpleName
        }
    }

    describe("HibernateReactiveRepositoryFactoryBean") {

        context("getObject") {

            it("creates a proxy for a repository interface") {
                val factoryBean = HibernateReactiveRepositoryFactoryBean(TestUserRepository::class.java)
                factoryBean.sessionProvider = sessionProvider
                factoryBean.transactionExecutor = transactionExecutor

                val proxy = factoryBean.getObject()

                proxy.shouldBeInstanceOf<TestUserRepository>()
            }

            it("handles a repository with generic type parameters") {
                val factoryBean = HibernateReactiveRepositoryFactoryBean(TestOrderRepository::class.java)
                factoryBean.sessionProvider = sessionProvider
                factoryBean.transactionExecutor = transactionExecutor

                val proxy = factoryBean.getObject()

                proxy.shouldBeInstanceOf<TestOrderRepository>()
            }
        }

        context("getObjectType") {

            it("returns the repository interface class") {
                val factoryBean = HibernateReactiveRepositoryFactoryBean(TestUserRepository::class.java)

                factoryBean.objectType shouldBe TestUserRepository::class.java
            }
        }

        context("isSingleton") {

            it("returns true") {
                val factoryBean = HibernateReactiveRepositoryFactoryBean(TestUserRepository::class.java)

                factoryBean.isSingleton shouldBe true
            }
        }

        context("extractGenericTypes error handling") {

            it("throws for an interface that does not extend CoroutineCrudRepository") {
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
        class User(val id: Long, val name: String)
        class Order(val id: String, val amount: Int)

        interface TestUserRepository : CoroutineCrudRepository<User, Long>
        interface TestOrderRepository : CoroutineCrudRepository<Order, String>

        interface InvalidRepository
    }
}
