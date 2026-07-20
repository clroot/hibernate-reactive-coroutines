package io.clroot.hibernate.reactive.spring.boot.transaction

import io.smallrye.mutiny.Uni
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.reactor.asCoroutineDispatcher
import kotlinx.coroutines.reactor.mono
import org.hibernate.reactive.mutiny.Mutiny
import org.springframework.transaction.reactive.TransactionContextManager
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import reactor.core.scheduler.Schedulers
import java.util.function.Function

class TransactionalAwareSessionProviderTest : DescribeSpec({

    fun usesStandaloneSessionWithInactiveSynchronization(resource: (() -> Any)? = null): Boolean {
        val sessionFactory = mockk<Mutiny.SessionFactory>()
        val standaloneSession = mockk<Mutiny.Session>()
        val provider = TransactionalAwareSessionProvider(sessionFactory)
        every { sessionFactory.withSession<Boolean>(any()) } answers {
            firstArg<Function<Mutiny.Session, Uni<Boolean>>>().apply(standaloneSession)
        }

        return TransactionSynchronizationManager.forCurrentTransaction()
            .doOnNext { tsm ->
                resource?.invoke()?.let { tsm.bindResource(sessionFactory, it) }
            }
            .then(
                mono {
                    provider.read { session ->
                        Uni.createFrom().item(session === standaloneSession)
                    }
                },
            )
            .contextWrite(TransactionContextManager.createTransactionContext())
            .block() == true
    }

    describe("transactional session lookup") {
        it("reuses the Spring transaction session on a Reactor non-blocking thread") {
            val sessionFactory = mockk<Mutiny.SessionFactory>()
            val transactionalSession = mockk<Mutiny.Session>()
            val holder = MutinySessionHolder(transactionalSession)
            val provider = TransactionalAwareSessionProvider(sessionFactory)
            val dispatcher = Schedulers.parallel().asCoroutineDispatcher()

            val usedTransactionalSession = TransactionSynchronizationManager.forCurrentTransaction()
                .doOnNext {
                    it.setActualTransactionActive(true)
                    it.bindResource(sessionFactory, holder)
                }
                .then(
                    mono(dispatcher) {
                        provider.read { session ->
                            Uni.createFrom().item(session === transactionalSession)
                        }
                    },
                )
                .contextWrite(TransactionContextManager.createTransactionContext())
                .block()

            usedTransactionalSession shouldBe true
        }

        it("uses a standalone session when synchronization exists without an actual transaction") {
            usesStandaloneSessionWithInactiveSynchronization() shouldBe true
        }

        it("ignores a stale session holder when no actual transaction is active") {
            usesStandaloneSessionWithInactiveSynchronization {
                MutinySessionHolder(mockk())
            } shouldBe true
        }

        it("ignores a malformed resource when no actual transaction is active") {
            usesStandaloneSessionWithInactiveSynchronization { Any() } shouldBe true
        }

        it("fails closed when an active Spring transaction has no Hibernate Reactive session") {
            val sessionFactory = mockk<Mutiny.SessionFactory>()
            val provider = TransactionalAwareSessionProvider(sessionFactory)

            val failure = shouldThrow<IllegalStateException> {
                TransactionSynchronizationManager.forCurrentTransaction()
                    .doOnNext { it.setActualTransactionActive(true) }
                    .then(
                        mono {
                            provider.read { Uni.createFrom().item(Unit) }
                        },
                    )
                    .contextWrite(TransactionContextManager.createTransactionContext())
                    .block()
            }

            failure.message shouldBe
                "No Hibernate Reactive session is bound to the active Spring transaction"
        }

        it("fails closed when an active Spring transaction has a malformed session resource") {
            val sessionFactory = mockk<Mutiny.SessionFactory>()
            val provider = TransactionalAwareSessionProvider(sessionFactory)

            val failure = shouldThrow<IllegalStateException> {
                TransactionSynchronizationManager.forCurrentTransaction()
                    .doOnNext {
                        it.setActualTransactionActive(true)
                        it.bindResource(sessionFactory, Any())
                    }
                    .then(
                        mono {
                            provider.write { Uni.createFrom().item(Unit) }
                        },
                    )
                    .contextWrite(TransactionContextManager.createTransactionContext())
                    .block()
            }

            failure.message shouldBe
                "No Hibernate Reactive session is bound to the active Spring transaction"
        }
    }
})
