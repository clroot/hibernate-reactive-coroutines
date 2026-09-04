package io.clroot.hibernate.reactive

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.mockk
import kotlinx.coroutines.withContext
import org.hibernate.reactive.mutiny.Mutiny
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Tests for coroutine-context behavior and timeout calculations. */
class ReactiveSessionContextTest : DescribeSpec({

    describe("ReactiveSessionContext") {

        context("construction") {
            it("reports read-write mode as writable") {
                val session = mockk<Mutiny.Session>()
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                )

                context.isReadOnly shouldBe false
                context.mode shouldBe TransactionMode.READ_WRITE
                context.session shouldBeSameInstanceAs session
            }

            it("reports read-only mode as read-only") {
                val session = mockk<Mutiny.Session>()
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_ONLY,
                )

                context.isReadOnly shouldBe true
                context.mode shouldBe TransactionMode.READ_ONLY
            }

            it("uses an infinite timeout by default") {
                val session = mockk<Mutiny.Session>()
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                )

                context.timeout shouldBe INFINITE
            }

            it("retains the configured timeout") {
                val session = mockk<Mutiny.Session>()
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                    timeout = 30.seconds,
                )

                context.timeout shouldBe 30.seconds
            }
        }

        context("remainingTimeout") {
            it("returns infinity for an infinite timeout") {
                val session = mockk<Mutiny.Session>()
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                    timeout = INFINITE,
                )

                context.remainingTimeout() shouldBe INFINITE
            }

            it("returns nearly the full timeout immediately after creation") {
                val session = mockk<Mutiny.Session>()
                val now = System.nanoTime()
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                    timeout = 10.seconds,
                    startTimeNanos = now,
                )

                val remaining = context.remainingTimeout()
                // Allow time elapsed while creating and checking the context.
                (remaining.inWholeMilliseconds >= 9900) shouldBe true
            }

            it("decreases as time elapses") {
                val session = mockk<Mutiny.Session>()
                val startTimeNanos = System.nanoTime() - 5_000_000_000L
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                    timeout = 10.seconds,
                    startTimeNanos = startTimeNanos,
                )

                val remaining = context.remainingTimeout()
                // Allow clock and execution-time variance around the expected five seconds.
                (remaining.inWholeMilliseconds in 4900..5100) shouldBe true
            }

            it("returns zero after the timeout expires") {
                val session = mockk<Mutiny.Session>()
                val startTimeNanos = System.nanoTime() - 15_000_000_000L
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                    timeout = 10.seconds,
                    startTimeNanos = startTimeNanos,
                )

                context.remainingTimeout() shouldBe 0.milliseconds
            }
        }

        context("coroutine context integration") {
            it("makes the context available within withContext") {
                val session = mockk<Mutiny.Session>()
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                )

                withContext(context) {
                    val retrieved = currentContextOrNull()
                    retrieved shouldBeSameInstanceAs context
                    retrieved?.session shouldBeSameInstanceAs session
                }
            }

            it("returns null from currentContextOrNull without a context") {
                val result = currentContextOrNull()
                result.shouldBeNull()
            }

            it("returns null from currentSessionOrNull without a context") {
                val result = currentSessionOrNull()
                result.shouldBeNull()
            }

            it("returns the session from the current context") {
                val session = mockk<Mutiny.Session>()
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                )

                withContext(context) {
                    currentSessionOrNull() shouldBeSameInstanceAs session
                }
            }
        }

        context("nested contexts") {
            it("uses the inner context until its scope exits") {
                val outerSession = mockk<Mutiny.Session>()
                val innerSession = mockk<Mutiny.Session>()

                val outerContext = ReactiveSessionContext(
                    session = outerSession,
                    mode = TransactionMode.READ_WRITE,
                )
                val innerContext = ReactiveSessionContext(
                    session = innerSession,
                    mode = TransactionMode.READ_ONLY,
                )

                withContext(outerContext) {
                    currentSessionOrNull() shouldBeSameInstanceAs outerSession
                    currentContextOrNull()?.isReadOnly shouldBe false

                    withContext(innerContext) {
                        currentSessionOrNull() shouldBeSameInstanceAs innerSession
                        currentContextOrNull()?.isReadOnly shouldBe true
                    }

                    currentSessionOrNull() shouldBeSameInstanceAs outerSession
                    currentContextOrNull()?.isReadOnly shouldBe false
                }
            }
        }
    }

    describe("TransactionMode") {
        it("defines read-only and read-write modes") {
            TransactionMode.entries.size shouldBe 2
            TransactionMode.entries shouldBe listOf(
                TransactionMode.READ_ONLY,
                TransactionMode.READ_WRITE,
            )
        }
    }
})
