package io.clroot.hibernate.reactive

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.withContext
import org.hibernate.reactive.mutiny.Mutiny
import java.util.function.Function

/** Tests session reuse and read-only enforcement for read and write helpers. */
class ReactiveSessionProviderTest : DescribeSpec({

    describe("ReactiveSessionProvider") {

        it("implements the framework-neutral session operations contract") {
            ReactiveSessionProvider(mockk()).shouldBeInstanceOf<ReactiveSessionOperations>()
        }

        context("read helper") {

            it("opens a session when no context exists") {
                val session = mockk<Mutiny.Session>()
                val sessionFactory = mockk<Mutiny.SessionFactory>()

                every {
                    sessionFactory.withSession(any<Function<Mutiny.Session, Uni<String>>>())
                } answers {
                    val block = firstArg<Function<Mutiny.Session, Uni<String>>>()
                    block.apply(session)
                }

                val provider = ReactiveSessionProvider(sessionFactory)

                val result = provider.read { s ->
                    s shouldBe session
                    Uni.createFrom().item("result")
                }

                result shouldBe "result"
                verify(exactly = 1) {
                    sessionFactory.withSession(any<Function<Mutiny.Session, Uni<String>>>())
                }
            }

            it("reuses the current session when a context exists") {
                val existingSession = mockk<Mutiny.Session>()
                val sessionFactory = mockk<Mutiny.SessionFactory>()
                val context = ReactiveSessionContext(
                    session = existingSession,
                    mode = TransactionMode.READ_WRITE,
                )

                val provider = ReactiveSessionProvider(sessionFactory)

                withContext(context) {
                    val result = provider.read { s ->
                        s shouldBe existingSession
                        Uni.createFrom().item("reused")
                    }

                    result shouldBe "reused"
                }

                verify(exactly = 0) {
                    sessionFactory.withSession(any<Function<Mutiny.Session, Uni<String>>>())
                }
            }

            it("uses the current session in a read-only context") {
                val existingSession = mockk<Mutiny.Session>()
                val sessionFactory = mockk<Mutiny.SessionFactory>()
                val context = ReactiveSessionContext(
                    session = existingSession,
                    mode = TransactionMode.READ_ONLY,
                )

                val provider = ReactiveSessionProvider(sessionFactory)

                withContext(context) {
                    val result = provider.read { s ->
                        s shouldBe existingSession
                        Uni.createFrom().item("read-only-ok")
                    }

                    result shouldBe "read-only-ok"
                }
            }
        }

        context("write helper") {

            it("opens a transaction when no context exists") {
                val session = mockk<Mutiny.Session>()
                val sessionFactory = mockk<Mutiny.SessionFactory>()

                every {
                    sessionFactory.withTransaction(any<Function<Mutiny.Session, Uni<String>>>())
                } answers {
                    val block = firstArg<Function<Mutiny.Session, Uni<String>>>()
                    block.apply(session)
                }

                val provider = ReactiveSessionProvider(sessionFactory)

                val result = provider.write { s ->
                    s shouldBe session
                    Uni.createFrom().item("written")
                }

                result shouldBe "written"
                verify(exactly = 1) {
                    sessionFactory.withTransaction(any<Function<Mutiny.Session, Uni<String>>>())
                }
            }

            it("reuses the current session in a read-write context") {
                val existingSession = mockk<Mutiny.Session>()
                val sessionFactory = mockk<Mutiny.SessionFactory>()
                val context = ReactiveSessionContext(
                    session = existingSession,
                    mode = TransactionMode.READ_WRITE,
                )

                val provider = ReactiveSessionProvider(sessionFactory)

                withContext(context) {
                    val result = provider.write { s ->
                        s shouldBe existingSession
                        Uni.createFrom().item("reused-write")
                    }

                    result shouldBe "reused-write"
                }

                verify(exactly = 0) {
                    sessionFactory.withTransaction(any<Function<Mutiny.Session, Uni<String>>>())
                }
            }

            it("throws ReadOnlyTransactionException in a read-only context") {
                val existingSession = mockk<Mutiny.Session>()
                val sessionFactory = mockk<Mutiny.SessionFactory>()
                val context = ReactiveSessionContext(
                    session = existingSession,
                    mode = TransactionMode.READ_ONLY,
                )

                val provider = ReactiveSessionProvider(sessionFactory)

                withContext(context) {
                    val exception = shouldThrow<ReadOnlyTransactionException> {
                        provider.write { Uni.createFrom().item("should-not-reach") }
                    }

                    exception.message shouldContain "read-only transaction"
                    exception.message shouldContain "tx.transactional"
                }
            }
        }

        context("ReadOnlyTransactionException") {
            it("retains its message") {
                val exception = ReadOnlyTransactionException("test message")
                exception.message shouldBe "test message"
            }

            it("extends IllegalStateException") {
                val exception = ReadOnlyTransactionException("test")
                (exception is IllegalStateException) shouldBe true
            }
        }
    }
})
