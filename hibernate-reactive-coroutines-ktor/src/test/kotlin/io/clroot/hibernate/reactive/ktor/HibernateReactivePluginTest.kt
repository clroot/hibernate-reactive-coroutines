package io.clroot.hibernate.reactive.ktor

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.repository.CoroutineCrudRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.mockk.verifyOrder
import io.vertx.core.Future
import io.vertx.core.Vertx
import org.hibernate.reactive.common.spi.Implementor
import org.hibernate.reactive.mutiny.Mutiny
import org.hibernate.reactive.vertx.VertxInstance
import org.hibernate.reactive.vertx.impl.ProvidedVertxInstance
import org.hibernate.service.ServiceRegistry
import java.lang.reflect.Proxy

class HibernateReactivePluginTest : DescribeSpec({
    describe("resource ownership") {
        it("does not open a request-wide session and preserves external resources by default") {
            val sessionFactory = mockk<Mutiny.SessionFactory>(relaxed = true)
            val vertx = Vertx.vertx()
            every { sessionFactory.close() } just runs

            try {
                testApplication {
                    application {
                        install(HibernateReactive) {
                            this.sessionFactory = sessionFactory
                            this.vertx = vertx
                        }
                        routing {
                            get("/ping") { call.respondText("pong") }
                        }
                    }

                    client.get("/ping").bodyAsText() shouldBe "pong"
                }

                confirmVerified(sessionFactory)
            } finally {
                vertx.close().toCompletionStage().toCompletableFuture().join()
            }
        }

        it("closes external resources only when explicitly configured") {
            val sessionFactory = mockk<Mutiny.SessionFactory>(relaxed = true)
            val vertx = mockk<Vertx>()
            every { sessionFactory.close() } just runs
            every { vertx.close() } returns Future.succeededFuture()

            testApplication {
                application {
                    install(HibernateReactive) {
                        this.sessionFactory = sessionFactory
                        this.vertx = vertx
                        closeExternalSessionFactory = true
                        closeExternalVertx = true
                    }
                }
                startApplication()
            }

            verify(exactly = 1) { sessionFactory.close() }
            verify(exactly = 1) { vertx.close() }
            verifyOrder {
                sessionFactory.close()
                vertx.close()
            }
        }
    }

    describe("registration and access") {
        it("rejects a Vert.x instance that does not match an external session factory") {
            val factoryVertx = Vertx.vertx()
            val configuredVertx = Vertx.vertx()
            val serviceRegistry = mockk<ServiceRegistry>()
            every { serviceRegistry.getService(VertxInstance::class.java) } returns
                ProvidedVertxInstance(factoryVertx)
            val sessionFactory = Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(Mutiny.SessionFactory::class.java, Implementor::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getServiceRegistry" -> serviceRegistry
                    "getUuid" -> "external-test"
                    "isOpen" -> true
                    "close" -> Unit
                    else -> null
                }
            } as Mutiny.SessionFactory

            try {
                val exception = shouldThrow<IllegalArgumentException> {
                    testApplication {
                        application {
                            install(HibernateReactive) {
                                this.sessionFactory = sessionFactory
                                vertx = configuredVertx
                            }
                        }
                    }
                }
                exception.message shouldContain "must be the instance used"
            } finally {
                factoryVertx.close().toCompletionStage().toCompletableFuture().join()
                configuredVertx.close().toCompletionStage().toCompletableFuture().join()
            }
        }

        it("registers a repository entity as a managed entity") {
            val configuration = HibernateReactiveConfiguration()

            configuration.repository<UnregisteredRepository, UnregisteredEntity, Long>()

            configuration.entityClasses shouldBe setOf(UnregisteredEntity::class.java)
        }

        it("optionally publishes infrastructure to Ktor dependency injection") {
            val sessionFactory = mockk<Mutiny.SessionFactory>(relaxed = true)
            val vertx = Vertx.vertx()
            every { sessionFactory.close() } just runs

            try {
                testApplication {
                    application {
                        install(HibernateReactive) {
                            this.sessionFactory = sessionFactory
                            this.vertx = vertx
                            dependencyInjection = true
                        }

                        val injectedResources: HibernateReactiveResources by dependencies
                        val injectedExecutor: ReactiveTransactionExecutor by dependencies
                        injectedResources.sessionFactory shouldBe hibernateSessionFactory
                        injectedExecutor shouldBe hibernateTransactionExecutor
                    }
                    startApplication()
                }

                verify(exactly = 0) { sessionFactory.close() }
            } finally {
                vertx.close().toCompletionStage().toCompletableFuture().join()
            }
        }
    }
})

private class UnregisteredEntity(val id: Long)

private interface UnregisteredRepository : CoroutineCrudRepository<UnregisteredEntity, Long>
