package io.clroot.hibernate.reactive

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.mockk
import io.mockk.confirmVerified
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.hibernate.reactive.mutiny.Mutiny
import kotlin.time.Duration
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ReactiveTransactionExecutorTest : DescribeSpec({
    describe("REQUIRED propagation") {
        for (parentMode in TransactionMode.entries) {
            it("reuses the $parentMode context for read-only work without changing its mode") {
                val factory = mockk<Mutiny.SessionFactory>()
                val session = mockk<Mutiny.Session>()
                val parent = ReactiveSessionContext(session, parentMode)
                val executor = ReactiveTransactionExecutor(factory)

                withContext(parent) {
                    executor.readOnly {
                        currentContextOrNull() shouldBeSameInstanceAs parent
                        currentSessionOrNull() shouldBeSameInstanceAs session
                        "result"
                    } shouldBe "result"
                    currentContextOrNull() shouldBeSameInstanceAs parent
                }
                currentContextOrNull() shouldBe null
                confirmVerified(factory, session)
            }
        }

        it("uses the current write transaction before consulting an ambient transaction probe") {
            val factory = mockk<Mutiny.SessionFactory>()
            val session = mockk<Mutiny.Session>()
            val parent = ReactiveSessionContext(session, TransactionMode.READ_WRITE)
            val executor = ReactiveTransactionExecutor(factory, AmbientTransactionProbe {
                error("The probe must not run when a coroutine transaction exists")
            })

            withContext(parent) {
                executor.transactional {
                    currentContextOrNull() shouldBeSameInstanceAs parent
                    "joined"
                } shouldBe "joined"
            }
            confirmVerified(factory)
        }

        it("rejects write work in a read-only context before invoking the block") {
            val factory = mockk<Mutiny.SessionFactory>()
            val executor = ReactiveTransactionExecutor(factory)
            val parent = ReactiveSessionContext(mockk(), TransactionMode.READ_ONLY)
            var invoked = false

            withContext(parent) {
                shouldThrow<ReadOnlyTransactionException> {
                    executor.transactional { invoked = true }
                }
            }
            invoked shouldBe false
            confirmVerified(factory)
        }

        for (readOnly in listOf(false, true)) {
            it("joins an ambient transaction with readOnly=$readOnly without opening a session") {
                val factory = mockk<Mutiny.SessionFactory>()
                var probes = 0
                val executor = ReactiveTransactionExecutor(factory, AmbientTransactionProbe {
                    probes++
                    AmbientTransaction(isReadOnly = readOnly)
                })

                executor.readOnly { "read" } shouldBe "read"
                if (readOnly) {
                    var invoked = false
                    shouldThrow<ReadOnlyTransactionException> {
                        executor.transactional { invoked = true }
                    }
                    invoked shouldBe false
                } else {
                    executor.transactional { "write" } shouldBe "write"
                }
                probes shouldBe 2
                confirmVerified(factory)
            }
        }
    }

    describe("effective timeout enforcement") {
        // Both sources must enforce the smaller budget, including either unbounded input.
        val budgets = listOf(
            Triple(10.seconds, 3.seconds, 3.seconds),
            Triple(3.seconds, 10.seconds, 3.seconds),
            Triple(INFINITE, 3.seconds, 3.seconds),
            Triple(3.seconds, INFINITE, 3.seconds),
            Triple(Duration.ZERO, INFINITE, Duration.ZERO),
            Triple(INFINITE, Duration.ZERO, Duration.ZERO),
        )
        for (ambient in listOf(false, true)) {
            for ((remaining, requested, expected) in budgets) {
                it("limits ${if (ambient) "ambient" else "coroutine"} work to $expected for $remaining remaining and $requested requested") {
                    runTest {
                        val factory = mockk<Mutiny.SessionFactory>()
                        val executor = ReactiveTransactionExecutor(factory, AmbientTransactionProbe {
                            AmbientTransaction(false, remaining)
                        })
                        var completed = false
                        var started = false
                        val block: suspend () -> Unit = {
                            executor.transactional(timeout = requested) {
                                started = true
                                delay(60.seconds)
                                completed = true
                            }
                        }

                        shouldThrow<TimeoutCancellationException> {
                            if (ambient) {
                                block()
                            } else {
                                val parent = ReactiveSessionContext(
                                    mockk(), TransactionMode.READ_WRITE, remaining,
                                    clock = MonotonicClock { testScheduler.currentTime * 1_000_000 },
                                )
                                withContext(parent) { block() }
                            }
                        }
                        testScheduler.currentTime shouldBe expected.inWholeMilliseconds
                        completed shouldBe false
                        started shouldBe (expected != Duration.ZERO)
                        confirmVerified(factory)
                    }
                }
            }
        }

        it("deducts elapsed parent time before joining and cancels suspended work") {
            runTest {
                val factory = mockk<Mutiny.SessionFactory>()
                val executor = ReactiveTransactionExecutor(factory)
                val parent = ReactiveSessionContext(
                    mockk(), TransactionMode.READ_WRITE, 10.seconds,
                    clock = MonotonicClock { testScheduler.currentTime * 1_000_000 },
                )
                var cleanedUp = false
                delay(7.seconds)

                withContext(parent) {
                    shouldThrow<TimeoutCancellationException> {
                        executor.readOnly(timeout = INFINITE) {
                            try {
                                awaitCancellation()
                            } finally {
                                cleanedUp = true
                            }
                        }
                    }
                    currentContextOrNull() shouldBeSameInstanceAs parent
                }
                testScheduler.currentTime shouldBe 10_000L
                cleanedUp shouldBe true
                confirmVerified(factory)
            }
        }

        it("allows unbounded work when both the ambient and requested timeout are infinite") {
            runTest {
                val factory = mockk<Mutiny.SessionFactory>()
                val executor = ReactiveTransactionExecutor(factory, AmbientTransactionProbe {
                    AmbientTransaction(false, INFINITE)
                })

                executor.transactional(timeout = INFINITE) {
                    delay(120.seconds)
                    "completed"
                } shouldBe "completed"
                testScheduler.currentTime shouldBe 120_000L
                confirmVerified(factory)
            }
        }

        it("enforces the default timeout when joining an unbounded ambient transaction") {
            runTest {
                val factory = mockk<Mutiny.SessionFactory>()
                val executor = ReactiveTransactionExecutor(factory, AmbientTransactionProbe {
                    AmbientTransaction(false)
                })

                shouldThrow<TimeoutCancellationException> {
                    executor.transactional { awaitCancellation() }
                }
                testScheduler.currentTime shouldBe 30_000L
                confirmVerified(factory)
            }
        }
    }

    describe("failure propagation") {
        it("cancels suspended nested work when its caller is cancelled") {
            runTest {
                val factory = mockk<Mutiny.SessionFactory>()
                val executor = ReactiveTransactionExecutor(factory)
                val parent = ReactiveSessionContext(mockk(), TransactionMode.READ_WRITE)
                val started = CompletableDeferred<Unit>()
                var cleanedUp = false
                val caller = launch(parent) {
                    executor.transactional(timeout = INFINITE) {
                        try {
                            started.complete(Unit)
                            awaitCancellation()
                        } finally {
                            cleanedUp = true
                        }
                    }
                }

                started.await()
                caller.cancelAndJoin()

                caller.isCancelled shouldBe true
                cleanedUp shouldBe true
                currentContextOrNull() shouldBe null
                confirmVerified(factory)
            }
        }

        for (failure in listOf(IllegalStateException("write failed"), CancellationException("caller cancelled"))) {
            it("propagates ${failure.javaClass.simpleName} and restores the caller context") {
                val factory = mockk<Mutiny.SessionFactory>()
                val executor = ReactiveTransactionExecutor(factory)
                val parent = ReactiveSessionContext(mockk(), TransactionMode.READ_WRITE)

                withContext(parent) {
                    val thrown = shouldThrow<Exception> {
                        executor.transactional<Unit> { throw failure }
                    }
                    // Coroutine stack-trace recovery may copy exceptions across a timeout scope.
                    thrown.javaClass shouldBe failure.javaClass
                    thrown.message shouldBe failure.message
                    currentContextOrNull() shouldBeSameInstanceAs parent
                }
                currentContextOrNull() shouldBe null
                confirmVerified(factory)
            }
        }
    }
})
