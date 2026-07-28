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
 * Spring `@Transactional`이 시작한 리액티브 트랜잭션을 감지합니다.
 *
 * 이 훅이 없으면 `@Transactional` 안에서 `tx.transactional {}`을 호출할 때
 * 별도의 세션과 트랜잭션이 열리지만, 정작 Repository 호출은
 * [TransactionalAwareSessionProvider]를 통해 Spring 세션으로 향합니다.
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
