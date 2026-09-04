package io.clroot.hibernate.reactive.test.recorder

import org.hibernate.reactive.mutiny.Mutiny

/**
 * Session proxy that records query creation.
 *
 * Delegation preserves normal session behavior while intercepting query creation for assertions.
 */
class RecordingSessionWrapper(
    private val delegate: Mutiny.Session,
    private val recorder: HqlRecorder,
) : Mutiny.Session by delegate {

    override fun <R : Any?> createQuery(queryString: String, resultClass: Class<R>): Mutiny.SelectionQuery<R> {
        recorder.record(
            RecordedQuery(
                hql = queryString,
                queryType = detectSelectQueryType(queryString),
            ),
        )
        return delegate.createQuery(queryString, resultClass)
    }

    override fun createMutationQuery(queryString: String): Mutiny.MutationQuery {
        recorder.record(
            RecordedQuery(
                hql = queryString,
                queryType = detectMutationQueryType(queryString),
            ),
        )
        return delegate.createMutationQuery(queryString)
    }

    override fun <R : Any?> createNativeQuery(queryString: String, resultClass: Class<R>): Mutiny.SelectionQuery<R> {
        recorder.record(
            RecordedQuery(
                hql = queryString,
                queryType = QueryType.NATIVE,
            ),
        )
        return delegate.createNativeQuery(queryString, resultClass)
    }

    private fun detectSelectQueryType(query: String): QueryType {
        val trimmed = query.trim().uppercase()
        return when {
            trimmed.startsWith("SELECT COUNT") -> QueryType.COUNT
            trimmed.startsWith("SELECT") -> QueryType.SELECT
            trimmed.startsWith("FROM") -> QueryType.SELECT
            else -> QueryType.SELECT
        }
    }

    private fun detectMutationQueryType(query: String): QueryType {
        val trimmed = query.trim().uppercase()
        return when {
            trimmed.startsWith("DELETE") -> QueryType.DELETE
            trimmed.startsWith("UPDATE") -> QueryType.UPDATE
            else -> QueryType.UPDATE
        }
    }
}
