package io.clroot.hibernate.reactive.spring.boot.transaction

import org.hibernate.reactive.mutiny.Mutiny
import org.hibernate.reactive.pool.ReactiveConnection
import org.hibernate.reactive.session.ReactiveConnectionSupplier

/**
 * Extracts the Hibernate Reactive connection backing a Mutiny session.
 *
 * Hibernate Reactive does not expose this through the public Mutiny API. The direct supplier path
 * is preferred, with the tested MutinySessionImpl delegate layout as a compatibility fallback.
 */
internal object ReactiveConnectionAccessor {

    fun get(session: Mutiny.Session): ReactiveConnection {
        if (session is ReactiveConnectionSupplier) {
            return session.reactiveConnection
        }

        return try {
            val delegateField = session.javaClass.getDeclaredField("delegate")
            delegateField.isAccessible = true
            val delegate = delegateField.get(session)

            if (delegate is ReactiveConnectionSupplier) {
                delegate.reactiveConnection
            } else {
                throw IllegalStateException(
                    "Cannot extract ReactiveConnection from ${session.javaClass.name}. " +
                        "Delegate ${delegate?.javaClass?.name} does not implement ReactiveConnectionSupplier",
                )
            }
        } catch (error: NoSuchFieldException) {
            throw IllegalStateException(
                "Cannot extract ReactiveConnection from ${session.javaClass.name}. No 'delegate' field found.",
                error,
            )
        }
    }
}
