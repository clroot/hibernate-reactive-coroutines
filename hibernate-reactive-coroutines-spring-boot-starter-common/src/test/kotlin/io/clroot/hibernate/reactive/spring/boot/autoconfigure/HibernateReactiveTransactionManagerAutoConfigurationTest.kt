package io.clroot.hibernate.reactive.spring.boot.autoconfigure

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import io.vertx.core.Vertx
import org.hibernate.SessionFactory
import org.hibernate.reactive.mutiny.Mutiny
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.transaction.ReactiveTransactionManager

class HibernateReactiveTransactionManagerAutoConfigurationTest : DescribeSpec({
    describe("Hibernate Reactive transaction manager auto-configuration") {
        it("backs off when another ReactiveTransactionManager exists") {
            contextRunner()
                .withBean(
                    "r2dbcTransactionManager",
                    ReactiveTransactionManager::class.java,
                    { mockk<ReactiveTransactionManager>() },
                )
                .run { context ->
                    context.getBeanNamesForType(ReactiveTransactionManager::class.java).toList() shouldContainExactly
                            listOf("r2dbcTransactionManager")
                }
        }

        it("registers its own transaction manager when none exists") {
            contextRunner().run { context ->
                context.getBeanNamesForType(ReactiveTransactionManager::class.java).toList() shouldContainExactly
                        listOf("hibernateReactiveTransactionManager")
            }
        }
    }

    describe("session factory auto-configuration") {
        it("does not build a second session factory when the user supplies a Mutiny.SessionFactory") {
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HibernateReactiveAutoConfiguration::class.java))
                .withBean("userSessionFactory", Mutiny.SessionFactory::class.java, { mockk<Mutiny.SessionFactory>() })
                .run { context ->
                    context.getBeanNamesForType(Mutiny.SessionFactory::class.java).toList() shouldContainExactly
                            listOf("userSessionFactory")
                    context.containsBean("hibernateSessionFactory") shouldBe false
                    context.getBeanNamesForType(Vertx::class.java).toList() shouldBe emptyList()
                }
        }

        it("starts without spring.datasource.url when the user supplies a Mutiny.SessionFactory") {
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HibernateReactiveAutoConfiguration::class.java))
                .withBean("userSessionFactory", Mutiny.SessionFactory::class.java, { mockk<Mutiny.SessionFactory>() })
                .run { context ->
                    context.startupFailure shouldBe null
                }
        }

        it("fails with an actionable message when spring.datasource.url is missing") {
            // Override the JVM property configured by the Testcontainers fixture.
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HibernateReactiveAutoConfiguration::class.java))
                .withPropertyValues("spring.datasource.url=")
                .run { context ->
                    val failure = context.startupFailure.shouldNotBeNull()
                    failure.stackTraceToString() shouldContain "spring.datasource.url"
                }
        }
    }
})

private fun contextRunner(): ApplicationContextRunner =
    ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(HibernateReactiveAutoConfiguration::class.java))
        .withBean("hibernateSessionFactory", SessionFactory::class.java, { mockk<SessionFactory>() })
        .withBean("reactiveSessionFactory", Mutiny.SessionFactory::class.java, { mockk<Mutiny.SessionFactory>() })
        .withPropertyValues(
            "spring.datasource.url=jdbc:postgresql://localhost/test",
            "spring.datasource.username=test",
            "spring.datasource.password=test",
            "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        )
