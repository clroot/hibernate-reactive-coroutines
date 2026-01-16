package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.spring.boot.repository.query.ParameterStyle
import io.clroot.hibernate.reactive.spring.boot.repository.query.PreparedQueryMethod
import io.clroot.hibernate.reactive.spring.boot.repository.query.QueryConstants.ORDER_BY_REGEX
import io.clroot.hibernate.reactive.spring.boot.repository.query.QueryReturnType
import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import org.hibernate.reactive.mutiny.Mutiny
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort

/**
 * 쿼리 실행을 담당하는 내부 헬퍼 클래스.
 *
 * PartTree 기반 쿼리와 @Query 어노테이션 쿼리의 실행을 처리합니다.
 * 페이징 관련 쿼리는 PaginationOperations에서 처리합니다.
 *
 * @param T 엔티티 타입
 */
internal class QueryOperations<T : Any>(
    private val entityClass: Class<T>,
    private val sessionProvider: TransactionalAwareSessionProvider,
) {
    // ============================================
    // PartTree 쿼리 실행
    // ============================================

    suspend fun executeSingleQuery(hql: String, args: List<Any?>): T? =
        sessionProvider.read { session ->
            val query = session.createQuery(hql, entityClass)
            bindIndexedParameters(query, args)
            query.singleResultOrNull
        }

    suspend fun executeListQuery(hql: String, args: List<Any?>): List<T> =
        sessionProvider.read { session ->
            val query = session.createQuery(hql, entityClass)
            bindIndexedParameters(query, args)
            query.resultList
        }

    suspend fun executeExistsQuery(hql: String, args: List<Any?>): Boolean {
        val count = sessionProvider.read { session ->
            val query = session.createQuery(hql, Long::class.javaObjectType)
            bindIndexedParameters(query, args)
            query.singleResult
        }
        return (count ?: 0L) > 0
    }

    suspend fun executeCountQuery(hql: String, args: List<Any?>): Long =
        sessionProvider.read { session ->
            val query = session.createQuery(hql, Long::class.javaObjectType)
            bindIndexedParameters(query, args)
            query.singleResult
        } ?: 0L

    suspend fun executeDeleteQuery(hql: String, args: List<Any?>) {
        sessionProvider.write<Unit> { session ->
            val query = session.createMutationQuery(hql)
            bindIndexedParameters(query, args)
            query.executeUpdate().replaceWith(Unit)
        }
    }

    suspend fun executeListQueryWithSort(
        prepared: PreparedQueryMethod,
        args: List<Any?>,
        sort: Sort,
    ): List<T> {
        val hql = applyDynamicSort(prepared.hql, sort)
        return executeListQuery(hql, args)
    }

    // ============================================
    // @Query 어노테이션 쿼리 실행
    // ============================================

    suspend fun executeModifyingAnnotatedQuery(
        prepared: PreparedQueryMethod,
        args: List<Any?>,
    ): Int {
        if (prepared.isNativeQuery) {
            throw UnsupportedOperationException(
                "@Modifying with native query is not yet supported in Hibernate Reactive. Use JPQL instead.",
            )
        }

        return sessionProvider.write { session ->
            val query = session.createMutationQuery(prepared.hql)
            bindAnnotatedParameters(query, prepared, args)
            query.executeUpdate()
        }
    }

    suspend fun executeListAnnotatedQuery(
        prepared: PreparedQueryMethod,
        args: List<Any?>,
    ): List<T> {
        return sessionProvider.read { session ->
            val query = if (prepared.isNativeQuery) {
                session.createNativeQuery(prepared.hql, entityClass)
            } else {
                session.createQuery(prepared.hql, entityClass)
            }

            bindAnnotatedParameters(query, prepared, args)
            query.resultList
        }
    }

    suspend fun executeSingleAnnotatedQuery(
        prepared: PreparedQueryMethod,
        args: List<Any?>,
    ): T? {
        return sessionProvider.read { session ->
            val query = if (prepared.isNativeQuery) {
                session.createNativeQuery(prepared.hql, entityClass)
            } else {
                session.createQuery(prepared.hql, entityClass)
            }

            bindAnnotatedParameters(query, prepared, args)
            query.singleResultOrNull
        }
    }

    // ============================================
    // 파라미터 바인딩 헬퍼
    // ============================================

    private fun <R> bindIndexedParameters(
        query: Mutiny.SelectionQuery<R>,
        args: List<Any?>,
    ) {
        args.forEachIndexed { index, arg ->
            query.setParameter("p$index", arg)
        }
    }

    private fun bindIndexedParameters(
        query: Mutiny.MutationQuery,
        args: List<Any?>,
    ) {
        args.forEachIndexed { index, arg ->
            query.setParameter("p$index", arg)
        }
    }

    internal fun <R> bindAnnotatedParameters(
        query: Mutiny.SelectionQuery<R>,
        prepared: PreparedQueryMethod,
        args: List<Any?>,
    ) {
        when (prepared.parameterStyle) {
            ParameterStyle.NAMED -> {
                prepared.parameterNames.forEachIndexed { index, name ->
                    query.setParameter(name, args[index])
                }
            }

            ParameterStyle.POSITIONAL -> {
                args.forEachIndexed { index, arg ->
                    query.setParameter(index + 1, arg)
                }
            }

            ParameterStyle.NONE -> { /* 파라미터 없음 */
            }
        }
    }

    private fun bindAnnotatedParameters(
        query: Mutiny.MutationQuery,
        prepared: PreparedQueryMethod,
        args: List<Any?>,
    ) {
        when (prepared.parameterStyle) {
            ParameterStyle.NAMED -> {
                prepared.parameterNames.forEachIndexed { index, name ->
                    query.setParameter(name, args[index])
                }
            }

            ParameterStyle.POSITIONAL -> {
                args.forEachIndexed { index, arg ->
                    query.setParameter(index + 1, arg)
                }
            }

            ParameterStyle.NONE -> { /* 파라미터 없음 */
            }
        }
    }

    // ============================================
    // Sort 유틸리티
    // ============================================

    internal fun applyDynamicSort(hql: String, sort: Sort): String {
        if (sort.isUnsorted) return hql

        val baseHql = hql.replace(ORDER_BY_REGEX, "")
        val sortClause = buildSortClause(sort)

        return "$baseHql ORDER BY $sortClause"
    }

    internal fun buildSortClause(sort: Sort): String {
        if (sort.isUnsorted) return ""
        return sort.map { order ->
            val direction = if (order.isAscending) "ASC" else "DESC"
            "e.${order.property} $direction"
        }.joinToString(", ")
    }
}
