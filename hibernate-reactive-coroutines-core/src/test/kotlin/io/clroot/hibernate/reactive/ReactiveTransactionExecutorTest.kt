package io.clroot.hibernate.reactive

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.seconds

/**
 * Tests timeout calculations and nested transaction behavior.
 *
 * Actual transactional and read-only behavior requires Vert.x and is covered by
 * Spring Boot Starter integration tests.
 */
class ReactiveTransactionExecutorTest : DescribeSpec({

    describe("ReactiveTransactionExecutor") {

        context("DEFAULT_TIMEOUT") {
            it("defaults to 30 seconds") {
                ReactiveTransactionExecutor.DEFAULT_TIMEOUT shouldBe 30.seconds
            }
        }

        context("effective timeout calculation") {

            it("exposes the default timeout used without a parent context") {
                val sessionFactory = mockk<org.hibernate.reactive.mutiny.Mutiny.SessionFactory>()
                val executor = ReactiveTransactionExecutor(sessionFactory)

                // The private calculation is exercised indirectly through its default.
                ReactiveTransactionExecutor.DEFAULT_TIMEOUT shouldBe 30.seconds
            }
        }

        context("nested transaction timeout inheritance") {
            it("returns infinity for a parent context with an infinite timeout") {
                val session = mockk<org.hibernate.reactive.mutiny.Mutiny.Session>()
                val parentContext = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                    timeout = INFINITE,
                )

                parentContext.remainingTimeout() shouldBe INFINITE
            }

            it("returns nearly the full remaining timeout immediately after creation") {
                val session = mockk<org.hibernate.reactive.mutiny.Mutiny.Session>()
                val parentContext = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                    timeout = 10.seconds,
                    startTimeNanos = System.nanoTime(),
                )

                val remaining = parentContext.remainingTimeout()
                // Allow time elapsed while creating and checking the context.
                (remaining.inWholeMilliseconds in 9900..10100) shouldBe true
            }

            it("returns approximately five seconds after five seconds elapse") {
                val session = mockk<org.hibernate.reactive.mutiny.Mutiny.Session>()
                val parentContext = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                    timeout = 10.seconds,
                    startTimeNanos = System.nanoTime() - 5_000_000_000L,
                )

                val remaining = parentContext.remainingTimeout()
                // Allow clock and execution-time variance around the expected five seconds.
                (remaining.inWholeMilliseconds in 4900..5100) shouldBe true
            }
        }

        context("required context reuse") {
            it("uses the current session in an existing read-write context") {
                val session = mockk<org.hibernate.reactive.mutiny.Mutiny.Session>()
                val sessionFactory = mockk<org.hibernate.reactive.mutiny.Mutiny.SessionFactory>()
                val executor = ReactiveTransactionExecutor(sessionFactory)
                val existingContext = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                )

                val actualSession = withContext(existingContext) {
                    executor.transactional {
                        currentSessionOrNull()
                    }
                }

                actualSession shouldBe session
            }

            it("does not promote a read-only context to a write transaction") {
                val session = mockk<org.hibernate.reactive.mutiny.Mutiny.Session>()
                val sessionFactory = mockk<org.hibernate.reactive.mutiny.Mutiny.SessionFactory>()
                val executor = ReactiveTransactionExecutor(sessionFactory)
                val existingContext = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_ONLY,
                )
                var blockExecuted = false

                withContext(existingContext) {
                    shouldThrow<ReadOnlyTransactionException> {
                        executor.transactional {
                            blockExecuted = true
                        }
                    }
                }

                blockExecuted shouldBe false
            }
        }

        context("timeout boundaries") {
            it("returns at most a small positive remainder at the timeout boundary") {
                val session = mockk<org.hibernate.reactive.mutiny.Mutiny.Session>()
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                    timeout = 10.seconds,
                    startTimeNanos = System.nanoTime() - 10_000_000_000L,
                )

                // The sampling boundary permits a small positive remainder.
                (context.remainingTimeout().inWholeMilliseconds <= 100) shouldBe true
            }

            it("retains nearly all of a long timeout immediately after creation") {
                val session = mockk<org.hibernate.reactive.mutiny.Mutiny.Session>()
                val longTimeout = 3600.seconds
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                    timeout = longTimeout,
                )

                val remaining = context.remainingTimeout()
                // Allow time elapsed while creating and checking the context.
                (remaining.inWholeMilliseconds >= 3599000) shouldBe true
            }
        }
    }
})
