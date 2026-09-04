package io.clroot.hibernate.reactive.spring.boot.transaction

import io.clroot.hibernate.reactive.MonotonicClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.smallrye.mutiny.Uni
import io.vertx.sqlclient.spi.DatabaseMetadata
import org.hibernate.reactive.mutiny.Mutiny
import org.hibernate.reactive.pool.ReactiveConnection
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionTimedOutException
import org.springframework.transaction.UnexpectedRollbackException
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

class HibernateReactiveTransactionManagerTimeoutTest : DescribeSpec({

    describe("transaction begin deadline") {
        it("installs PostgreSQL statement timeout after beginning the transaction") {
            val events = mutableListOf<String>()
            val clock = TestMonotonicClock(1_000)
            val sessionFactory = mockk<Mutiny.SessionFactory>()
            val session = mockk<Mutiny.Session>()
            val holder = MutinySessionHolder(
                session,
                timeout = 1.seconds,
                clock = clock,
            )
            val metadata = mockk<DatabaseMetadata>()
            val connection = mockk<ReactiveConnection>()
            val manager = HibernateReactiveTransactionManager(sessionFactory)

            every { metadata.productName() } returns "PostgreSQL"
            every { connection.databaseMetadata } returns metadata
            every { connection.beginTransaction() } answers {
                events += "begin"
                CompletableFuture.completedFuture(null)
            }
            every { connection.executeUnprepared(any()) } answers {
                events += firstArg<String>()
                CompletableFuture.completedFuture(null)
            }

            manager.beginTransaction(holder, connection, TransactionDefinition.ISOLATION_DEFAULT)
                .await().indefinitely()

            events.first() shouldBe "begin"
            events.single { it.startsWith("SET LOCAL statement_timeout = ") } shouldBe
                    "SET LOCAL statement_timeout = 1000"
        }

        it("rolls back when PostgreSQL statement timeout setup fails") {
            val events = mutableListOf<String>()
            val sessionFactory = mockk<Mutiny.SessionFactory>()
            val session = mockk<Mutiny.Session>()
            val holder = MutinySessionHolder(session, timeout = 1.seconds)
            val metadata = mockk<DatabaseMetadata>()
            val connection = mockk<ReactiveConnection>()
            val manager = HibernateReactiveTransactionManager(sessionFactory)

            every { metadata.productName() } returns "PostgreSQL"
            every { connection.databaseMetadata } returns metadata
            every { connection.beginTransaction() } answers {
                events += "begin"
                CompletableFuture.completedFuture(null)
            }
            every { connection.executeUnprepared(any()) } answers {
                events += firstArg<String>()
                CompletableFuture.failedFuture(IllegalStateException("setup failed"))
            }
            every { connection.rollbackTransaction() } answers {
                events += "rollback"
                CompletableFuture.completedFuture(null)
            }

            shouldThrow<IllegalStateException> {
                manager.beginTransaction(holder, connection, TransactionDefinition.ISOLATION_DEFAULT)
                    .await().indefinitely()
            }.message shouldBe "setup failed"

            events.first() shouldBe "begin"
            events.last() shouldBe "rollback"
            events.size shouldBe 3
        }
    }

    describe("transaction commit deadline") {
        it("flushes and commits while the deadline is active") {
            val sessionFactory = mockk<Mutiny.SessionFactory>()
            val session = mockk<Mutiny.Session>()
            val connection = mockk<ReactiveConnection>()
            val holder = MutinySessionHolder(session)
            val manager = HibernateReactiveTransactionManager(sessionFactory)

            every { session.flush() } returns Uni.createFrom().voidItem()
            every { connection.commitTransaction() } returns CompletableFuture.completedFuture(null)

            manager.completeTransaction(holder, session, connection)
                .await().indefinitely()

            verifyOrder {
                session.flush()
                connection.commitTransaction()
            }
            verify(exactly = 0) { connection.rollbackTransaction() }
        }

        it("rolls back when the deadline expires during flush") {
            val clock = TestMonotonicClock(1_000)
            val sessionFactory = mockk<Mutiny.SessionFactory>()
            val session = mockk<Mutiny.Session>()
            val connection = mockk<ReactiveConnection>()
            val metadata = mockk<DatabaseMetadata>()
            val holder = MutinySessionHolder(
                session,
                timeout = 1.seconds,
                clock = clock,
            )
            val manager = HibernateReactiveTransactionManager(sessionFactory)

            every { metadata.productName() } returns "H2"
            every { connection.databaseMetadata } returns metadata
            every { session.flush() } returns
                    Uni.createFrom().voidItem()
                        .invoke { _: Void? -> clock.advance(1.seconds) }
            every { connection.rollbackTransaction() } returns CompletableFuture.completedFuture(null)

            val error = shouldThrow<UnexpectedRollbackException> {
                manager.completeTransaction(holder, session, connection)
                    .await().indefinitely()
            }
            error.mostSpecificCause.shouldBeInstanceOf<TransactionTimedOutException>()

            verifyOrder {
                session.flush()
                connection.rollbackTransaction()
            }
            verify(exactly = 0) { connection.commitTransaction() }
        }

        it("maps a deadline failure during flush and rolls back") {
            val sessionFactory = mockk<Mutiny.SessionFactory>()
            val session = mockk<Mutiny.Session>()
            val connection = mockk<ReactiveConnection>()
            val holder = MutinySessionHolder(session)
            val manager = HibernateReactiveTransactionManager(sessionFactory)
            val databaseError = IllegalStateException("statement timeout")

            every { session.flush() } answers {
                holder.markTransactionTimedOut()
                Uni.createFrom().failure(databaseError)
            }
            every { connection.rollbackTransaction() } returns CompletableFuture.completedFuture(null)

            val error = shouldThrow<UnexpectedRollbackException> {
                manager.completeTransaction(holder, session, connection)
                    .await().indefinitely()
            }
            val timeout = error.cause.shouldBeInstanceOf<TransactionTimedOutException>()
            timeout.cause shouldBe databaseError

            verifyOrder {
                session.flush()
                connection.rollbackTransaction()
            }
            verify(exactly = 0) { connection.commitTransaction() }
        }

        it("maps a deadline failure during commit and rolls back") {
            val sessionFactory = mockk<Mutiny.SessionFactory>()
            val session = mockk<Mutiny.Session>()
            val connection = mockk<ReactiveConnection>()
            val holder = MutinySessionHolder(session)
            val manager = HibernateReactiveTransactionManager(sessionFactory)
            val databaseError = IllegalStateException("commit statement timeout")

            every { session.flush() } returns Uni.createFrom().voidItem()
            every { connection.commitTransaction() } answers {
                holder.markTransactionTimedOut()
                CompletableFuture.failedFuture(databaseError)
            }
            every { connection.rollbackTransaction() } returns CompletableFuture.completedFuture(null)

            val error = shouldThrow<UnexpectedRollbackException> {
                manager.completeTransaction(holder, session, connection)
                    .await().indefinitely()
            }
            val timeout = error.cause.shouldBeInstanceOf<TransactionTimedOutException>()
            timeout.cause shouldBe databaseError

            verifyOrder {
                session.flush()
                connection.commitTransaction()
                connection.rollbackTransaction()
            }
        }

        it("rolls back and preserves a non-timeout flush failure") {
            val sessionFactory = mockk<Mutiny.SessionFactory>()
            val session = mockk<Mutiny.Session>()
            val connection = mockk<ReactiveConnection>()
            val holder = MutinySessionHolder(session)
            val manager = HibernateReactiveTransactionManager(sessionFactory)
            val databaseError = IllegalStateException("flush failed")

            every { session.flush() } returns Uni.createFrom().failure(databaseError)
            every { connection.rollbackTransaction() } returns CompletableFuture.completedFuture(null)

            shouldThrow<IllegalStateException> {
                manager.completeTransaction(holder, session, connection)
                    .await().indefinitely()
            } shouldBe databaseError

            verifyOrder {
                session.flush()
                connection.rollbackTransaction()
            }
            verify(exactly = 0) { connection.commitTransaction() }
        }
    }
})

private class TestMonotonicClock(
    private var nowNanos: Long,
) : MonotonicClock {
    override fun nanoTime(): Long = nowNanos

    fun advance(duration: kotlin.time.Duration) {
        nowNanos += duration.inWholeNanoseconds
    }
}
