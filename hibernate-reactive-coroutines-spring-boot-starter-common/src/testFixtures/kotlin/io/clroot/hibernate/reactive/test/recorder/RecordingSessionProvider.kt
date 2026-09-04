package io.clroot.hibernate.reactive.test.recorder

import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import io.smallrye.mutiny.Uni
import org.hibernate.reactive.mutiny.Mutiny

/**
 * Test session provider that records HQL queries.
 *
 * Wraps every session so queries are recorded without changing production code.
 */
internal class RecordingSessionProvider(
    sessionFactory: Mutiny.SessionFactory,
    private val recorder: HqlRecorder,
) : TransactionalAwareSessionProvider(sessionFactory) {

    override suspend fun <T> read(block: (Mutiny.Session) -> Uni<T>): T {
        return super.read { session ->
            block(RecordingSessionWrapper(session, recorder))
        }
    }

    override suspend fun <T> write(block: (Mutiny.Session) -> Uni<T>): T {
        return super.write { session ->
            block(RecordingSessionWrapper(session, recorder))
        }
    }
}
