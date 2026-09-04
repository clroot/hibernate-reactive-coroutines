package io.clroot.hibernate.reactive.spring.boot.transaction

import io.clroot.hibernate.reactive.AmbientTransaction
import io.clroot.hibernate.reactive.AmbientTransactionProbe
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.hibernate.reactive.mutiny.Mutiny
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.reactive.TransactionSynchronizationManager

/**
 * Detects a reactive transaction started by Spring `@Transactional`.
 *
 * This prevents nested `tx.transactional {}` calls from opening a session that differs
 * from the Spring-bound session used by repositories.
 */
public class SpringAmbientTransactionProbe(
    private val sessionFactory: Mutiny.SessionFactory,
) : AmbientTransactionProbe {

    override suspend fun currentTransaction(): AmbientTransaction? {
        val reactorContext = currentCoroutineContext()[ReactorContext]?.context
            ?: return null

        return try {
            TransactionSynchronizationManager.forCurrentTransaction()
                .mapNotNull { synchronizationManager ->
                    if (!synchronizationManager.isActualTransactionActive) {
                        return@mapNotNull null
                    }

                    val holder = synchronizationManager.getResource(sessionFactory) as? MutinySessionHolder
                        ?: return@mapNotNull null
                    val sessionContext = holder.toReactiveSessionContext()

                    AmbientTransaction(
                        isReadOnly = sessionContext.isReadOnly,
                        remainingTimeout = sessionContext.remainingTimeout(),
                    )
                }
                .contextWrite(reactorContext)
                .awaitSingleOrNull()
        } catch (_: NoTransactionException) {
            null
        }
    }
}
