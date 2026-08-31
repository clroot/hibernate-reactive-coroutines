package io.clroot.hibernate.reactive.spring.boot.transaction

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.smallrye.mutiny.Uni
import org.hibernate.reactive.mutiny.Mutiny
import org.hibernate.reactive.pool.ReactiveConnection
import org.springframework.transaction.TransactionTimedOutException
import org.springframework.transaction.UnexpectedRollbackException
import java.util.concurrent.CompletableFuture

class HibernateReactiveTransactionManagerTimeoutTest : DescribeSpec({

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
            val sessionFactory = mockk<Mutiny.SessionFactory>()
            val session = mockk<Mutiny.Session>()
            val connection = mockk<ReactiveConnection>()
            val holder = MutinySessionHolder(session)
            val manager = HibernateReactiveTransactionManager(sessionFactory)

            every { session.flush() } returns
                    Uni.createFrom().voidItem()
                        .invoke { _: Void? -> holder.markTransactionTimedOut() }
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
    }
})
