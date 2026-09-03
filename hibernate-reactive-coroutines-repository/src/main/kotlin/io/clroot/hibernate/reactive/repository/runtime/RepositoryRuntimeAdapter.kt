package io.clroot.hibernate.reactive.repository.runtime

import io.clroot.hibernate.reactive.repository.query.derived.QueryOrder

/** Normalized paging request passed between a framework adapter and the repository runtime. */
public data class RepositoryPageRequest(
    public val offset: Long,
    public val pageSize: Int,
    public val sort: List<QueryOrder> = emptyList(),
    /** Opaque integration value used when constructing the framework-native result. */
    public val context: Any? = null,
) {
    init {
        require(offset >= 0) { "Page offset must not be negative" }
        require(pageSize > 0) { "Page size must be greater than zero" }
    }
}

/** Framework-neutral standalone sort argument for direct runtime use. */
public data class RepositorySort(
    public val orders: List<QueryOrder> = emptyList(),
)

/** Arguments after framework-specific paging and sorting parameters have been removed. */
public data class RepositoryInvocationArguments(
    public val queryArguments: List<Any?>,
    public val pageRequest: RepositoryPageRequest? = null,
    public val sort: List<QueryOrder> = emptyList(),
    /** Distinguishes an explicitly supplied unsorted value from the absence of a sort parameter. */
    public val hasSortParameter: Boolean = false,
)

/** Neutral page transport returned by the default adapter. */
public data class RepositoryPage<T>(
    public val content: List<T>,
    public val request: RepositoryPageRequest,
    public val totalElements: Long,
)

/** Neutral slice transport returned by the default adapter. */
public data class RepositorySlice<T>(
    public val content: List<T>,
    public val request: RepositoryPageRequest,
    public val hasNext: Boolean,
)

/**
 * Integration boundary for native pagination/sort arguments and result containers.
 *
 * Spring and Jakarta Data adapters translate their own public API types here. The default
 * implementation allows a repository proxy to operate without any application framework.
 */
public interface RepositoryRuntimeAdapter {
    public fun adaptArguments(arguments: List<Any?>): RepositoryInvocationArguments {
        if (arguments.isEmpty()) return RepositoryInvocationArguments(arguments)
        return when (val special = arguments.last()) {
            is RepositoryPageRequest -> RepositoryInvocationArguments(
                queryArguments = arguments.dropLast(1),
                pageRequest = special,
                sort = special.sort,
                hasSortParameter = true,
            )
            is RepositorySort -> RepositoryInvocationArguments(
                queryArguments = arguments.dropLast(1),
                sort = special.orders,
                hasSortParameter = true,
            )
            else -> RepositoryInvocationArguments(arguments)
        }
    }

    public fun createPage(
        content: List<*>,
        request: RepositoryPageRequest,
        totalElements: Long,
    ): Any = RepositoryPage(content, request, totalElements)

    public fun createSlice(
        content: List<*>,
        request: RepositoryPageRequest,
        hasNext: Boolean,
    ): Any = RepositorySlice(content, request, hasNext)

    public companion object {
        /** Adapter for repositories that do not expose framework-specific paging types. */
        public val DEFAULT: RepositoryRuntimeAdapter = object : RepositoryRuntimeAdapter {}
    }
}
