package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.repository.query.derived.QueryOrder
import io.clroot.hibernate.reactive.repository.query.derived.SortDirection
import io.clroot.hibernate.reactive.repository.runtime.RepositoryEntityLifecycle
import io.clroot.hibernate.reactive.repository.runtime.RepositoryInvocationArguments
import io.clroot.hibernate.reactive.repository.runtime.RepositoryPageRequest
import io.clroot.hibernate.reactive.repository.runtime.RepositoryRuntimeAdapter
import io.clroot.hibernate.reactive.spring.boot.auditing.ReactiveAuditingHandler
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Persistable
import org.springframework.data.domain.SliceImpl
import org.springframework.data.domain.Sort

/** Maps Spring Data paging and sorting values at the neutral runtime boundary. */
internal object SpringRepositoryRuntimeAdapter : RepositoryRuntimeAdapter {
    override fun adaptArguments(arguments: List<Any?>): RepositoryInvocationArguments {
        if (arguments.isEmpty()) return RepositoryInvocationArguments(arguments)

        return when (val special = arguments.last()) {
            is Pageable -> {
                val sort = special.sort.toQueryOrders()
                RepositoryInvocationArguments(
                    queryArguments = arguments.dropLast(1),
                    pageRequest = RepositoryPageRequest(
                        offset = special.offset,
                        pageSize = special.pageSize,
                        sort = sort,
                        context = special,
                    ),
                    sort = sort,
                    hasSortParameter = true,
                )
            }
            is Sort -> RepositoryInvocationArguments(
                queryArguments = arguments.dropLast(1),
                sort = special.toQueryOrders(),
                hasSortParameter = true,
            )
            else -> RepositoryInvocationArguments(arguments)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun createPage(content: List<*>, request: RepositoryPageRequest, totalElements: Long): Any {
        val pageable = request.context as? Pageable
            ?: error("Spring repository page result requires a Pageable context")
        return PageImpl<Any>(content as List<Any>, pageable, totalElements)
    }

    @Suppress("UNCHECKED_CAST")
    override fun createSlice(content: List<*>, request: RepositoryPageRequest, hasNext: Boolean): Any {
        val pageable = request.context as? Pageable
            ?: error("Spring repository slice result requires a Pageable context")
        return SliceImpl<Any>(content as List<Any>, pageable, hasNext)
    }
}

/** Preserves Spring Data Persistable precedence and reactive auditing behavior. */
internal class SpringRepositoryEntityLifecycle(
    private val auditingHandler: ReactiveAuditingHandler<*>?,
) : RepositoryEntityLifecycle {
    override fun isNew(entity: Any): Boolean? =
        (entity as? Persistable<*>)?.isNew

    override suspend fun beforeSave(entity: Any, isNew: Boolean) {
        val handler = auditingHandler ?: return
        if (isNew) handler.markCreated(entity) else handler.markModified(entity)
    }
}

private fun Sort.toQueryOrders(): List<QueryOrder> = map { order ->
    QueryOrder(
        property = order.property,
        direction = if (order.isAscending) SortDirection.ASC else SortDirection.DESC,
        ignoreCase = order.isIgnoreCase,
    )
}.toList()
