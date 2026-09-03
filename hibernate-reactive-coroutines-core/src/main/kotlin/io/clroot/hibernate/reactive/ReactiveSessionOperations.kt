package io.clroot.hibernate.reactive

import io.smallrye.mutiny.Uni
import org.hibernate.reactive.mutiny.Mutiny

/**
 * Framework-neutral contract for executing work with a Hibernate Reactive session.
 *
 * Integrations may reuse an ambient transaction before falling back to a new session or transaction.
 * Implementations must reject [write] calls made inside a read-only transaction.
 */
public interface ReactiveSessionOperations {
    /** Executes a read operation with an existing or newly opened session. */
    public suspend fun <T> read(block: (Mutiny.Session) -> Uni<T>): T

    /** Executes a write operation with an existing or newly opened transactional session. */
    public suspend fun <T> write(block: (Mutiny.Session) -> Uni<T>): T
}
