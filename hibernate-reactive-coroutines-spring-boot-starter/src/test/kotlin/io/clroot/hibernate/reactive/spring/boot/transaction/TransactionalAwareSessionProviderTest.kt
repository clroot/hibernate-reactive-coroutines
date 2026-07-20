package io.clroot.hibernate.reactive.spring.boot.transaction

import io.smallrye.mutiny.Uni
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.reactor.asCoroutineDispatcher
import kotlinx.coroutines.reactor.mono
import org.hibernate.reactive.mutiny.Mutiny
import org.springframework.transaction.reactive.TransactionContextManager
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import reactor.core.scheduler.Schedulers

class TransactionalAwareSessionProviderTest : DescribeSpec({

    describe("transactional session lookup") {
        it("reuses the Spring transaction session on a Reactor non-blocking thread") {
            val sessionFactory = mockk<Mutiny.SessionFactory>()
            val transactionalSession = mockk<Mutiny.Session>()
            val holder = MutinySessionHolder(transactionalSession)
            val provider = TransactionalAwareSessionProvider(sessionFactory)
            val dispatcher = Schedulers.parallel().asCoroutineDispatcher()

            val usedTransactionalSession = TransactionSynchronizationManager.forCurrentTransaction()
                .doOnNext { it.bindResource(sessionFactory, holder) }
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
    }
})
