package io.clroot.hibernate.reactive

import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.asUni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.*
import org.hibernate.FlushMode
import org.hibernate.reactive.mutiny.Mutiny
import kotlin.time.Duration
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.seconds

/**
 * Executes related persistence operations in one reactive transaction.
 *
 * The block must remain on the Vert.x event loop. Do not detach work with
 * `launch` or `async`, switch dispatchers, or perform long-running external I/O
 * while it holds the database connection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class ReactiveTransactionExecutor @JvmOverloads constructor(
    private val sessionFactory: Mutiny.SessionFactory,
    private val ambientTransactionProbe: AmbientTransactionProbe? = null,
) {
    public companion object {
        public val DEFAULT_TIMEOUT: Duration = 30.seconds
    }

    /**
     * Executes [block] in a write transaction.
     *
     * Nested calls reuse the existing session. A read-only context cannot be
     * promoted to a write transaction.
     *
     * @param timeout transaction timeout; nested calls use the shorter remaining timeout
     * @throws ReadOnlyTransactionException when called in a read-only context
     */
    public suspend fun <T> transactional(
        timeout: Duration = DEFAULT_TIMEOUT,
        block: suspend () -> T,
    ): T = executeInSession(
        mode = TransactionMode.READ_WRITE,
        timeout = timeout,
        sessionStarter = { callback -> sessionFactory.withTransaction(callback) },
        block = block,
    )

    /**
     * Executes [block] in a read-only session.
     *
     * New sessions use Hibernate read-only mode and manual flushing, preventing
     * changes to loaded entities from being flushed automatically. Nested calls
     * reuse the existing session.
     *
     * @param timeout session timeout; nested calls use the shorter remaining timeout
     */
    public suspend fun <T> readOnly(
        timeout: Duration = DEFAULT_TIMEOUT,
        block: suspend () -> T,
    ): T = executeInSession(
        mode = TransactionMode.READ_ONLY,
        timeout = timeout,
        sessionStarter = { callback ->
            sessionFactory.withSession { session ->
                session
                    .setDefaultReadOnly(true)
                    .setFlushMode(FlushMode.MANUAL)
                callback.apply(session)
            }
        },
        block = block,
    )

    /**
     * Executes [block] with REQUIRED-style session propagation.
     */
    private suspend fun <T> executeInSession(
        mode: TransactionMode,
        timeout: Duration,
        sessionStarter: (java.util.function.Function<Mutiny.Session, Uni<T>>) -> Uni<T>,
        block: suspend () -> T,
    ): T {
        val parentContext = currentContextOrNull()
        if (mode == TransactionMode.READ_WRITE && parentContext?.isReadOnly == true) {
            throw ReadOnlyTransactionException(
                "Cannot start a write transaction within a read-only context. " +
                        "Move tx.transactional {} outside tx.readOnly {}",
            )
        }

        // Framework-managed transactions are not in the coroutine context.
        // Detect them to avoid opening an unused session and transaction.
        val ambientTransaction = if (parentContext == null) {
            ambientTransactionProbe?.currentTransaction()
        } else {
            null
        }
        if (mode == TransactionMode.READ_WRITE && ambientTransaction?.isReadOnly == true) {
            throw ReadOnlyTransactionException(
                "Cannot start a write transaction within a read-only transaction. " +
                        "Remove readOnly = true from the surrounding @Transactional annotation.",
            )
        }

        val effectiveTimeout = calculateEffectiveTimeout(parentContext, ambientTransaction, timeout)
        val callerContext = currentCoroutineContext().minusKey(Job)

        return if (parentContext != null || ambientTransaction != null) {
            // Join the existing transaction with REQUIRED-style propagation.
            executeWithTimeout(effectiveTimeout) { block() }
        } else {
            // Hibernate Reactive sessions must execute on the Vert.x event loop.
            executeWithTimeout(effectiveTimeout) {
                sessionStarter { session ->
                    val vertxDispatcher = requireVertxContext().dispatcher()
                    CoroutineScope(callerContext + vertxDispatcher)
                        .async {
                            val newContext = ReactiveSessionContext(
                                session = session,
                                mode = mode,
                                timeout = effectiveTimeout,
                            )
                            withContext(newContext) {
                                block()
                            }
                        }.asUni()
                }.awaitSuspending()
            }
        }
    }

    /**
     * Returns the current Vert.x context.
     *
     * @throws IllegalStateException when no Vert.x context is available
     */
    private fun requireVertxContext(): io.vertx.core.Context {
        return Vertx.currentContext()
            ?: throw IllegalStateException(
                "ReactiveTransactionExecutor must be called within a Vert.x context. " +
                        "Ensure the application is running on Vert.x or within a reactive pipeline."
            )
    }

    private fun calculateEffectiveTimeout(
        parentContext: ReactiveSessionContext?,
        ambientTransaction: AmbientTransaction?,
        timeout: Duration,
    ): Duration {
        val remaining = parentContext?.remainingTimeout()
            ?: ambientTransaction?.remainingTimeout
            ?: return timeout

        return when {
            remaining == INFINITE -> timeout
            timeout == INFINITE -> remaining
            else -> minOf(timeout, remaining)
        }
    }

    private suspend inline fun <T> executeWithTimeout(
        timeout: Duration,
        crossinline block: suspend () -> T,
    ): T =
        if (timeout == INFINITE) {
            block()
        } else {
            withTimeout(timeout) { block() }
        }
}
