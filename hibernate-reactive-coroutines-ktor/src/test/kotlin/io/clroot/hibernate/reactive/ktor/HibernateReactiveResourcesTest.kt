package io.clroot.hibernate.reactive.ktor

import io.clroot.hibernate.reactive.ReactiveSessionProvider
import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.mockk.verifyOrder
import io.vertx.core.Future
import io.vertx.core.Vertx
import org.hibernate.reactive.mutiny.Mutiny
import java.util.concurrent.ExecutionException

class HibernateReactiveResourcesTest : DescribeSpec({
    describe("resource cleanup") {
        for (closeFactory in listOf(false, true)) {
            for (closeVertx in listOf(false, true)) {
                it("honors independent ownership flags factory=$closeFactory and vertx=$closeVertx, exactly once") {
                    val factory = mockk<Mutiny.SessionFactory>()
                    val vertx = mockk<Vertx>()
                    every { factory.close() } just runs
                    every { vertx.close() } returns Future.succeededFuture()
                    val resources = resources(factory, vertx, closeFactory, closeVertx)

                    resources.close()
                    resources.close()

                    verify(exactly = if (closeFactory) 1 else 0) { factory.close() }
                    verify(exactly = if (closeVertx) 1 else 0) { vertx.close() }
                    if (closeFactory && closeVertx) {
                        verifyOrder {
                            factory.close()
                            vertx.close()
                        }
                    }
                }
            }
        }

        it("still closes Vert.x when closing the session factory fails") {
            val factory = mockk<Mutiny.SessionFactory>()
            val vertx = mockk<Vertx>()
            val failure = IllegalStateException("factory close failed")
            every { factory.close() } throws failure
            every { vertx.close() } returns Future.succeededFuture()
            val resources = resources(factory, vertx)

            shouldThrow<IllegalStateException> { resources.close() } shouldBeSameInstanceAs failure
            resources.close()

            verify(exactly = 1) { factory.close() }
            verify(exactly = 1) { vertx.close() }
            failure.suppressed.toList() shouldBe emptyList()
        }

        it("retains the factory failure and suppresses a failed asynchronous Vert.x close") {
            val factory = mockk<Mutiny.SessionFactory>()
            val vertx = mockk<Vertx>()
            val factoryFailure = IllegalStateException("factory close failed")
            val vertxFailure = IllegalArgumentException("Vert.x close failed")
            every { factory.close() } throws factoryFailure
            every { vertx.close() } returns Future.failedFuture(vertxFailure)
            val resources = resources(factory, vertx)

            val thrown = shouldThrow<IllegalStateException> { resources.close() }
            thrown shouldBeSameInstanceAs factoryFailure
            thrown.suppressed.size shouldBe 1
            thrown.suppressed.single().cause shouldBeSameInstanceAs vertxFailure
            resources.close()

            verify(exactly = 1) { factory.close() }
            verify(exactly = 1) { vertx.close() }
        }

        it("surfaces an asynchronous Vert.x close failure when the factory closes successfully") {
            val factory = mockk<Mutiny.SessionFactory>()
            val vertx = mockk<Vertx>()
            val failure = IllegalStateException("Vert.x close failed")
            every { factory.close() } just runs
            every { vertx.close() } returns Future.failedFuture(failure)
            val resources = resources(factory, vertx)

            shouldThrow<ExecutionException> { resources.close() }.cause shouldBeSameInstanceAs failure
            resources.close()

            verify(exactly = 1) { factory.close() }
            verify(exactly = 1) { vertx.close() }
        }
    }
})

private fun resources(
    factory: Mutiny.SessionFactory,
    vertx: Vertx,
    closeFactory: Boolean = true,
    closeVertx: Boolean = true,
): HibernateReactiveResources = HibernateReactiveResources(
    sessionFactory = factory,
    sessionProvider = ReactiveSessionProvider(factory),
    transactionExecutor = ReactiveTransactionExecutor(factory),
    vertx = vertx,
    repositories = emptyMap(),
    closeSessionFactory = closeFactory,
    closeVertx = closeVertx,
)
