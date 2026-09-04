package io.clroot.hibernate.reactive.repository

import io.clroot.hibernate.reactive.repository.query.derived.QueryOrder
import io.clroot.hibernate.reactive.repository.query.derived.SortDirection
import io.clroot.hibernate.reactive.repository.runtime.RepositoryInvocationArguments
import io.clroot.hibernate.reactive.repository.runtime.RepositoryPageRequest
import io.clroot.hibernate.reactive.repository.runtime.RepositoryRuntimeAdapter
import jakarta.data.Order
import jakarta.data.Sort
import jakarta.data.page.Page
import jakarta.data.page.PageRequest
import java.util.NoSuchElementException

/** Maps Jakarta Data pagination and sorting values to the shared repository runtime. */
internal object JakartaDataRepositoryRuntimeAdapter : RepositoryRuntimeAdapter {
    override fun adaptArguments(arguments: List<Any?>): RepositoryInvocationArguments {
        if (arguments.isEmpty()) return RepositoryInvocationArguments(arguments)

        val queryArguments = mutableListOf<Any?>()
        val sort = mutableListOf<QueryOrder>()
        var pageRequest: PageRequest? = null
        var hasSortParameter = false

        arguments.forEach { argument ->
            when (argument) {
                is PageRequest -> {
                    require(pageRequest == null) { "A repository method cannot accept more than one PageRequest" }
                    pageRequest = argument
                }
                is Order<*> -> {
                    hasSortParameter = true
                    sort += argument.sorts().map { it.toQueryOrder() }
                }
                is Sort<*> -> {
                    hasSortParameter = true
                    sort += argument.toQueryOrder()
                }
                is Array<*> -> if (argument.isSortArray()) {
                    hasSortParameter = true
                    sort += argument.filterIsInstance<Sort<*>>().map { it.toQueryOrder() }
                } else {
                    queryArguments.add(argument)
                }
                else -> queryArguments += argument
            }
        }

        val neutralPageRequest = pageRequest?.toRepositoryPageRequest(sort)
        return RepositoryInvocationArguments(
            queryArguments = queryArguments,
            pageRequest = neutralPageRequest,
            sort = sort,
            hasSortParameter = hasSortParameter,
        )
    }

    override fun shouldRequestTotal(request: RepositoryPageRequest): Boolean =
        (request.context as? PageRequest)?.requestTotal() ?: true

    override fun pageQueryLimit(request: RepositoryPageRequest): Int =
        if (!shouldRequestTotal(request) && request.pageSize < Int.MAX_VALUE) request.pageSize + 1 else request.pageSize

    @Suppress("UNCHECKED_CAST")
    override fun createPage(
        content: List<*>,
        request: RepositoryPageRequest,
        totalElements: Long,
    ): Any {
        val pageRequest = request.context as? PageRequest
            ?: error("Jakarta Data page result requires a PageRequest context")
        val hasNextWithoutTotals = if (!pageRequest.requestTotal() && request.pageSize < Int.MAX_VALUE) {
            content.size > request.pageSize
        } else {
            null
        }
        return JakartaDataPage(
            content = (content as List<Any>).take(request.pageSize),
            request = pageRequest,
            totalElements = totalElements.takeIf { pageRequest.requestTotal() },
            hasNextWithoutTotals = hasNextWithoutTotals,
        )
    }
}

private fun PageRequest.toRepositoryPageRequest(sort: List<QueryOrder>): RepositoryPageRequest {
    require(mode() == PageRequest.Mode.OFFSET) {
        "Cursor-based Jakarta Data pagination is not supported; use an offset PageRequest"
    }
    val offset = try {
        Math.multiplyExact(page() - 1L, size().toLong())
    } catch (exception: ArithmeticException) {
        throw IllegalArgumentException("Page offset exceeds the supported range", exception)
    }
    return RepositoryPageRequest(
        offset = offset,
        pageSize = size(),
        sort = sort,
        context = this,
    )
}

private fun Sort<*>.toQueryOrder(): QueryOrder = QueryOrder(
    property = property(),
    direction = if (isAscending()) SortDirection.ASC else SortDirection.DESC,
    ignoreCase = ignoreCase(),
)

private fun Array<*>.isSortArray(): Boolean =
    javaClass.componentType?.let(Sort::class.java::isAssignableFrom) == true

private class JakartaDataPage<T : Any>(
    private val content: List<T>,
    private val request: PageRequest,
    private val totalElements: Long?,
    private val hasNextWithoutTotals: Boolean?,
) : Page<T> {
    override fun content(): List<T> = content

    override fun hasContent(): Boolean = content.isNotEmpty()

    override fun numberOfElements(): Int = content.size

    override fun hasNext(): Boolean = totalElements?.let { total ->
        currentOffset() + content.size < total
    } ?: hasNextWithoutTotals ?: (content.size == request.size())

    override fun hasPrevious(): Boolean = request.page() > 1L

    override fun pageRequest(): PageRequest = request

    override fun nextPageRequest(): PageRequest {
        if (!hasNext()) throw NoSuchElementException("There is no next page")
        val nextPage = try {
            Math.addExact(request.page(), 1L)
        } catch (exception: ArithmeticException) {
            throw NoSuchElementException("The next page number exceeds the supported range").apply {
                initCause(exception)
            }
        }
        return PageRequest.ofPage(nextPage, request.size(), request.requestTotal())
    }

    override fun previousPageRequest(): PageRequest {
        if (!hasPrevious()) throw NoSuchElementException("There is no previous page")
        return PageRequest.ofPage(request.page() - 1L, request.size(), request.requestTotal())
    }

    override fun hasTotals(): Boolean = totalElements != null

    override fun totalElements(): Long = totalElements
        ?: throw IllegalStateException("Total elements were not requested")

    override fun totalPages(): Long {
        val total = totalElements()
        return if (total == 0L) 0L else ((total - 1L) / request.size()) + 1L
    }

    override fun iterator(): MutableIterator<T> = content.toMutableList().iterator()

    private fun currentOffset(): Long = Math.multiplyExact(request.page() - 1L, request.size().toLong())
}
