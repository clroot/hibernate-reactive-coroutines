package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.spring.boot.repository.collision.alpha.OrderRepository as AlphaOrderRepository
import io.clroot.hibernate.reactive.spring.boot.repository.collision.beta.OrderRepository as BetaOrderRepository
import io.clroot.hibernate.reactive.spring.boot.repository.collision.customer.CustomerRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.context.support.GenericApplicationContext
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

class HibernateReactiveRepositoryBeanNameGeneratorTest : DescribeSpec({
    fun registerRepositories(
        context: GenericApplicationContext,
        vararg basePackages: String,
    ) {
        HibernateReactiveRepositoryRegistrar(basePackages.toList()).apply {
            setApplicationContext(context)
            postProcessBeanDefinitionRegistry(context)
        }
    }

    describe("HibernateReactiveRepositoryBeanNameGenerator") {
        it("uses the conventional simple bean name for a unique repository") {
            val repository = Alpha.CustomerRepository::class.java

            HibernateReactiveRepositoryBeanNameGenerator.generate(listOf(repository)) { true }
                .shouldContainExactly(mapOf(repository to "customerRepository"))
        }

        it("uses fully qualified names for repositories with the same simple name") {
            val first = Alpha.OrderRepository::class.java
            val second = Beta.OrderRepository::class.java

            HibernateReactiveRepositoryBeanNameGenerator.generate(listOf(first, second)) { true }
                .shouldContainExactly(
                    mapOf(
                        first to first.name,
                        second to second.name,
                    ),
                )
        }

        it("uses the fully qualified name when the simple name is already taken") {
            val repository = Alpha.CustomerRepository::class.java

            HibernateReactiveRepositoryBeanNameGenerator.generate(listOf(repository)) {
                it != "customerRepository"
            }.shouldContainExactly(mapOf(repository to repository.name))
        }

        it("registers repositories with the same simple name from separate packages") {
            GenericApplicationContext().use { context ->
                registerRepositories(
                    context,
                    "io.clroot.hibernate.reactive.spring.boot.repository.collision.alpha",
                    "io.clroot.hibernate.reactive.spring.boot.repository.collision.beta",
                )

                context.containsBeanDefinition(AlphaOrderRepository::class.java.name) shouldBe true
                context.containsBeanDefinition(BetaOrderRepository::class.java.name) shouldBe true
            }
        }

        it("uses the fully qualified name when an alias occupies the simple name") {
            GenericApplicationContext().use { context ->
                context.registerBeanDefinition("existingBean", RootBeanDefinition(Any::class.java))
                context.registerAlias("existingBean", "customerRepository")

                registerRepositories(
                    context,
                    "io.clroot.hibernate.reactive.spring.boot.repository.collision.customer",
                )

                context.isAlias("customerRepository") shouldBe true
                context.containsBeanDefinition(CustomerRepository::class.java.name) shouldBe true
            }
        }

        it("uses the fully qualified name when a singleton occupies the simple name") {
            GenericApplicationContext().use { context ->
                context.beanFactory.registerSingleton("customerRepository", Any())

                registerRepositories(
                    context,
                    "io.clroot.hibernate.reactive.spring.boot.repository.collision.customer",
                )

                context.containsBeanDefinition(CustomerRepository::class.java.name) shouldBe true
            }
        }
    }

}) {
    private class TestEntity

    private object Alpha {
        interface CustomerRepository : CoroutineCrudRepository<TestEntity, Long>
        interface OrderRepository : CoroutineCrudRepository<TestEntity, Long>
    }

    private object Beta {
        interface OrderRepository : CoroutineCrudRepository<TestEntity, Long>
    }
}
