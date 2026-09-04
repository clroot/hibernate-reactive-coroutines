package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import jakarta.persistence.Id
import jakarta.persistence.metamodel.Metamodel
import org.hibernate.reactive.mutiny.Mutiny
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.lang.reflect.Proxy
import kotlin.coroutines.EmptyCoroutineContext

class SimpleHibernateReactiveRepositoryTest : DescribeSpec({

    val sessionProvider = mockk<TransactionalAwareSessionProvider>()
    val transactionExecutor = mockk<ReactiveTransactionExecutor>()
    every { sessionProvider.metamodel } returns mockk<Metamodel>(relaxed = true)

    fun createProxy(): TestRepository {
        val handler = SimpleHibernateReactiveRepository(
            entityClass = TestEntity::class.java,
            idClass = Long::class.java,
            sessionProvider = sessionProvider,
            transactionExecutor = transactionExecutor,
        )
        return Proxy.newProxyInstance(
            TestRepository::class.java.classLoader,
            arrayOf(TestRepository::class.java),
            handler,
        ) as TestRepository
    }

    fun createCustomProxy(): RepositoryWithCustomMethod {
        val handler = SimpleHibernateReactiveRepository(
            entityClass = TestEntity::class.java,
            idClass = Long::class.java,
            sessionProvider = sessionProvider,
            transactionExecutor = transactionExecutor,
        )
        return Proxy.newProxyInstance(
            RepositoryWithCustomMethod::class.java.classLoader,
            arrayOf(RepositoryWithCustomMethod::class.java),
            handler,
        ) as RepositoryWithCustomMethod
    }

    describe("SimpleHibernateReactiveRepository") {

        context("Object method handling") {

            it("returns the entity name, Repository, and proxy from toString") {
                val proxy = createProxy()

                proxy.toString() shouldContain "TestEntity"
                proxy.toString() shouldContain "Repository"
                proxy.toString() shouldContain "proxy"
            }

            it("returns a consistent hashCode") {
                val proxy = createProxy()

                val hash1 = proxy.hashCode()
                val hash2 = proxy.hashCode()

                hash1 shouldBe hash2
            }

            it("returns true when compared with itself") {
                val proxy = createProxy()

                (proxy == proxy) shouldBe true
            }

            it("returns false when compared with another proxy") {
                val proxy1 = createProxy()
                val proxy2 = createProxy()

                (proxy1 == proxy2) shouldBe false
            }

            it("is not equal to null") {
                val proxy = createProxy()
                proxy shouldNotBe null
            }

            it("returns different hashCodes for different proxies") {
                val proxy1 = createProxy()
                val proxy2 = createProxy()

                proxy1.hashCode() shouldNotBe proxy2.hashCode()
            }
        }

        context("error messages") {

            it("keeps the caller coroutine active after a repository exception") {
                val proxy = createCustomProxy()
                var caught = false

                try {
                    proxy.findByld(1L)
                } catch (_: UnsupportedOperationException) {
                    caught = true
                }

                caught shouldBe true
                currentCoroutineContext().isActive shouldBe true
            }

            it("suggests a similar method for an unknown invocation") {
                val handler = SimpleHibernateReactiveRepository(
                    entityClass = TestEntity::class.java,
                    idClass = Long::class.java,
                    sessionProvider = sessionProvider,
                    transactionExecutor = transactionExecutor,
                )

                val method = RepositoryWithCustomMethod::class.java.getMethod(
                    "findByld",
                    Long::class.java,
                    kotlin.coroutines.Continuation::class.java,
                )
                mockk<kotlin.coroutines.Continuation<Any?>> {
                    every { context } returns EmptyCoroutineContext
                }

                // Invocation failures are delivered to the continuation.
                var capturedError: Throwable? = null
                val capturingContinuation = object : kotlin.coroutines.Continuation<Any?> {
                    override val context = EmptyCoroutineContext
                    override fun resumeWith(result: Result<Any?>) {
                        capturedError = result.exceptionOrNull()
                    }
                }

                handler.invoke(mockk(), method, arrayOf(1L, capturingContinuation))

                delay(100)

                capturedError shouldNotBe null
                capturedError!!.message shouldContain "Unknown method: findByld"
                capturedError!!.message shouldContain "Did you mean"
                capturedError!!.message shouldContain "findById"
            }

            it("suggests save for a misspelled save invocation") {
                val handler = SimpleHibernateReactiveRepository(
                    entityClass = TestEntity::class.java,
                    idClass = Long::class.java,
                    sessionProvider = sessionProvider,
                    transactionExecutor = transactionExecutor,
                )

                val method = RepositoryWithCustomMethod::class.java.getMethod(
                    "sav",
                    TestEntity::class.java,
                    kotlin.coroutines.Continuation::class.java,
                )

                var capturedError: Throwable? = null
                val capturingContinuation = object : kotlin.coroutines.Continuation<Any?> {
                    override val context = EmptyCoroutineContext
                    override fun resumeWith(result: Result<Any?>) {
                        capturedError = result.exceptionOrNull()
                    }
                }

                handler.invoke(mockk(), method, arrayOf(TestEntity(), capturingContinuation))

                delay(100)

                capturedError shouldNotBe null
                capturedError!!.message shouldContain "Unknown method: sav"
                capturedError!!.message shouldContain "save"
            }
        }
    }
}) {
    companion object {
        interface TestRepository : CoroutineCrudRepository<TestEntity, Long>
        class TestEntity

        // Intentionally misspelled methods verify error-message suggestions.
        @Suppress("unused")
        interface RepositoryWithCustomMethod : CoroutineCrudRepository<TestEntity, Long> {
            @Suppress("SpellCheckingInspection")
            suspend fun findByld(id: Long): TestEntity?
            suspend fun sav(entity: TestEntity): TestEntity
        }
    }
}
