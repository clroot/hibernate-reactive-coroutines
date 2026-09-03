package io.clroot.hibernate.reactive.repository.runtime

import io.clroot.hibernate.reactive.ReactiveSessionOperations
import io.clroot.hibernate.reactive.repository.query.derived.QueryOrder

/** Executes framework-neutral page and slice queries. */
internal class PaginationOperations<T : Any>(
    private val entityClass: Class<T>,
    private val entityName: String,
    private val sessionOperations: ReactiveSessionOperations,
    private val queryOperations: QueryOperations<T>,
    private val runtimeAdapter: RepositoryRuntimeAdapter,
) {
    suspend fun findAllWithPageRequest(request: RepositoryPageRequest): Any {
        val baseHql = "FROM $entityName e"
        val sortClause = queryOperations.buildSortClause(request.sort)
        val hql = if (sortClause.isNotEmpty()) "$baseHql ORDER BY $sortClause" else baseHql
        val countHql = "SELECT COUNT(e) FROM $entityName e"

        val content = executeWithPaging(hql, emptyList(), request.pageSize, request.offset)
        val totalElements = calculateTotalElements(content, request, countHql, emptyList())
        return runtimeAdapter.createPage(content, request, totalElements)
    }

    suspend fun findAllWithSort(sort: List<QueryOrder>): List<T> {
        val baseHql = "FROM $entityName e"
        val sortClause = queryOperations.buildSortClause(sort)
        val hql = if (sortClause.isNotEmpty()) "$baseHql ORDER BY $sortClause" else baseHql

        return sessionOperations.read { session ->
            session.createQuery(hql, entityClass).resultList
        }
    }

    suspend fun executePageQuery(
        prepared: PreparedRepositoryQuery,
        args: List<Any?>,
        request: RepositoryPageRequest,
    ): Any {
        val hql = queryOperations.applyDynamicSort(prepared.hql, request.sort)
        val countHql = checkNotNull(prepared.countHql)
        val content = executeWithPaging(hql, args, request.pageSize, request.offset)
        val totalElements = calculateTotalElements(content, request, countHql, args)
        return runtimeAdapter.createPage(content, request, totalElements)
    }

    suspend fun executeSliceQuery(
        prepared: PreparedRepositoryQuery,
        args: List<Any?>,
        request: RepositoryPageRequest,
    ): Any {
        val hql = queryOperations.applyDynamicSort(prepared.hql, request.sort)
        val content = executeWithPaging(hql, args, request.pageSize + 1, request.offset)
        val hasNext = content.size > request.pageSize
        val sliceContent = if (hasNext) content.dropLast(1) else content
        return runtimeAdapter.createSlice(sliceContent, request, hasNext)
    }

    suspend fun executePageAnnotatedQuery(
        prepared: PreparedRepositoryQuery,
        args: List<Any?>,
        request: RepositoryPageRequest,
    ): Any {
        val hql = queryOperations.applyAnnotatedQuerySort(prepared, request.sort)
        val resultClass = queryOperations.annotatedResultClass(prepared)

        val content = sessionOperations.read { session ->
            val query = if (prepared.isNativeQuery) {
                session.createNativeQuery(hql, resultClass)
            } else {
                session.createQuery(hql, resultClass)
            }
            queryOperations.bindAnnotatedParameters(query, prepared, args)
            query.firstResult = request.offset.toHibernateFirstResult()
            query.maxResults = request.pageSize
            query.resultList
        }

        val totalElements = if (shouldSkipCountQuery(content, request)) {
            request.offset + content.size
        } else {
            executeCountAnnotatedQuery(prepared, args)
        }
        return runtimeAdapter.createPage(content, request, totalElements)
    }

    suspend fun executeSliceAnnotatedQuery(
        prepared: PreparedRepositoryQuery,
        args: List<Any?>,
        request: RepositoryPageRequest,
    ): Any {
        val hql = queryOperations.applyAnnotatedQuerySort(prepared, request.sort)
        val resultClass = queryOperations.annotatedResultClass(prepared)

        val content = sessionOperations.read { session ->
            val query = if (prepared.isNativeQuery) {
                session.createNativeQuery(hql, resultClass)
            } else {
                session.createQuery(hql, resultClass)
            }
            queryOperations.bindAnnotatedParameters(query, prepared, args)
            query.firstResult = request.offset.toHibernateFirstResult()
            query.maxResults = request.pageSize + 1
            query.resultList
        }

        val hasNext = content.size > request.pageSize
        val sliceContent = if (hasNext) content.dropLast(1) else content
        return runtimeAdapter.createSlice(sliceContent, request, hasNext)
    }

    private suspend fun executeCountAnnotatedQuery(
        prepared: PreparedRepositoryQuery,
        args: List<Any?>,
    ): Long {
        val countHql = checkNotNull(prepared.countHql)
        return sessionOperations.read { session ->
            val query = if (prepared.isNativeQuery) {
                session.createNativeQuery(countHql, Long::class.javaObjectType)
            } else {
                session.createQuery(countHql, Long::class.javaObjectType)
            }
            queryOperations.bindAnnotatedCountParameters(query, prepared, args)
            query.singleResult
        } ?: 0L
    }

    private suspend fun executeWithPaging(
        hql: String,
        args: List<Any?>,
        limit: Int,
        offset: Long,
    ): List<T> = sessionOperations.read { session ->
        val query = session.createQuery(hql, entityClass)
        args.forEachIndexed { index, arg -> query.setParameter("p$index", arg) }
        query.firstResult = offset.toHibernateFirstResult()
        query.maxResults = limit
        query.resultList
    }

    private suspend fun executeCountForPage(countHql: String, args: List<Any?>): Long =
        sessionOperations.read { session ->
            val query = session.createQuery(countHql, Long::class.javaObjectType)
            args.forEachIndexed { index, arg -> query.setParameter("p$index", arg) }
            query.singleResult
        } ?: 0L

    private suspend fun calculateTotalElements(
        content: List<T>,
        request: RepositoryPageRequest,
        countHql: String,
        args: List<Any?>,
    ): Long = if (shouldSkipCountQuery(content, request)) {
        request.offset + content.size
    } else {
        executeCountForPage(countHql, args)
    }

    private fun shouldSkipCountQuery(content: List<*>, request: RepositoryPageRequest): Boolean =
        (content.isNotEmpty() || request.offset == 0L) && content.size < request.pageSize
}

internal fun Long.toHibernateFirstResult(): Int {
    require(this in 0..Int.MAX_VALUE.toLong()) {
        "Page offset $this exceeds Hibernate's supported first-result range 0..${Int.MAX_VALUE}; " +
                "use a smaller page or keyset pagination"
    }
    return toInt()
}
