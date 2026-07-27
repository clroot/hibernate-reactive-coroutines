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
        it("유일한 리포지토리는 기존 단순 빈 이름을 유지한다") {
            val repository = Alpha.CustomerRepository::class.java

            HibernateReactiveRepositoryBeanNameGenerator.generate(listOf(repository)) { true }
                .shouldContainExactly(mapOf(repository to "customerRepository"))
        }

        it("서로 다른 패키지 경로의 동명 리포지토리는 완전한 클래스명으로 구분한다") {
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

        it("기존 빈이 단순 이름을 사용 중이면 완전한 클래스명으로 폴백한다") {
            val repository = Alpha.CustomerRepository::class.java

            HibernateReactiveRepositoryBeanNameGenerator.generate(listOf(repository)) {
                it != "customerRepository"
            }.shouldContainExactly(mapOf(repository to repository.name))
        }

        it("서로 다른 실제 패키지의 동명 리포지토리를 함께 등록한다") {
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

        it("alias가 단순 이름을 점유하면 완전한 클래스명으로 등록한다") {
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

        it("수동 singleton이 단순 이름을 점유하면 완전한 클래스명으로 등록한다") {
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
