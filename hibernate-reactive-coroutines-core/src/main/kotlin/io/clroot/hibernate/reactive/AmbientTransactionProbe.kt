package io.clroot.hibernate.reactive

import kotlin.time.Duration
import kotlin.time.Duration.Companion.INFINITE

/**
 * Metadata for a transaction started outside [ReactiveTransactionExecutor].
 *
 * @param isReadOnly whether the transaction is read-only
 * @param remainingTimeout remaining timeout, or [INFINITE] when unbounded
 */
public class AmbientTransaction(
    public val isReadOnly: Boolean,
    public val remainingTimeout: Duration = INFINITE,
)

/**
 * Detects an active transaction not represented in the coroutine context.
 *
 * The core module is framework-independent. Integrations use this hook to
 * detect framework-managed transactions and avoid opening an unused session.
 */
public fun interface AmbientTransactionProbe {

    /**
     * Returns the active transaction metadata, or `null` when none exists.
     */
    public suspend fun currentTransaction(): AmbientTransaction?
}
