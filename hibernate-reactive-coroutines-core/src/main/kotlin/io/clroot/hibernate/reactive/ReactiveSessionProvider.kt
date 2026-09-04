package io.clroot.hibernate.reactive

import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import org.hibernate.reactive.mutiny.Mutiny

/**
 * Thrown when a write is attempted in a read-only context.
 */
public class ReadOnlyTransactionException(
    message: String,
) : IllegalStateException(message)

/**
 * Provides Hibernate Reactive sessions to persistence adapters.
 *
 * Reuses the coroutine-bound session when present; otherwise opens a session
 * for reads or a transaction for writes.
 */
public class ReactiveSessionProvider(
    private val sessionFactory: Mutiny.SessionFactory,
) : ReactiveSessionOperations {
    /**
     * Executes a read using the current session or a new session.
     */
    override suspend fun <T> read(block: (Mutiny.Session) -> Uni<T>): T {
        val context = currentContextOrNull()
        return if (context != null) {
            block(context.session).awaitSuspending()
        } else {
            sessionFactory
                .withSession { session ->
                    block(session)
                }.awaitSuspending()
        }
    }

    /**
     * Executes a write using the current session or a new transaction.
     *
     * @throws ReadOnlyTransactionException when called in a read-only context
     */
    override suspend fun <T> write(block: (Mutiny.Session) -> Uni<T>): T {
        val context = currentContextOrNull()
        return when {
            context?.isReadOnly == true -> {
                throw ReadOnlyTransactionException(
                    "Cannot perform write operation in read-only transaction. " +
                            "Use tx.transactional {} instead of tx.readOnly {}",
                )
            }
            context != null -> {
                block(context.session).awaitSuspending()
            }
            else -> {
                sessionFactory
                    .withTransaction { session ->
                        block(session)
                    }.awaitSuspending()
            }
        }
    }
}
