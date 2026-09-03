package io.clroot.hibernate.reactive.repository.auditing

/**
 * Supplies the current auditor without blocking the reactive event loop.
 *
 * Returning `null` leaves auditor fields unchanged, which is appropriate for anonymous work.
 */
public fun interface ReactiveAuditorAware<T : Any> {
    /** Returns the current auditor, or `null` when no auditor is available. */
    public suspend fun getCurrentAuditor(): T?
}
