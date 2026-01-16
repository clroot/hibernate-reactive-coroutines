package io.clroot.hibernate.reactive.test.recorder

import org.hibernate.reactive.mutiny.Mutiny

/**
 * HQL 쿼리를 기록하는 Session 래퍼.
 *
 * [Mutiny.Session]의 쿼리 생성 메서드를 가로채서 [HqlRecorder]에 기록합니다.
 * 모든 다른 메서드는 원본 세션에 위임됩니다.
 *
 * @property delegate 원본 세션
 * @property recorder 쿼리 기록기
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
