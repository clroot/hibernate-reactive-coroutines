package io.clroot.hibernate.reactive.spring.boot.repository.query

import io.clroot.hibernate.reactive.repository.query.Modifying
import io.clroot.hibernate.reactive.repository.query.QueryParameterParser
import io.clroot.hibernate.reactive.repository.query.QueryParameterStyle
import io.clroot.hibernate.reactive.repository.query.derived.ParameterBinding
import io.clroot.hibernate.reactive.repository.runtime.PreparedRepositoryQuery
import io.clroot.hibernate.reactive.repository.runtime.RepositoryQueryKind
import io.clroot.hibernate.reactive.repository.runtime.RepositoryQueryReturnType
import kotlin.coroutines.Continuation
import org.springframework.data.repository.query.parser.PartTree
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/**
 * Query method metadata parsed at application startup.
 *
 * Caches derived-query parsing results and generated HQL to avoid runtime parsing.
 *
 * @param partTree Retained for compatibility; null for `@Query` methods.
 * @param countHql COUNT HQL for page results; null when no count query is needed.
 * @param parameterBinders Per-parameter transformations, such as LIKE escaping; empty for `@Query`.
 * @param parameterNames Names used for named parameters.
 * @param maxResults Maximum result count from `Top` or `First`; null when unlimited.
 */
internal data class PreparedQueryMethod(
    val method: Method,
    val partTree: PartTree?,
    val hql: String,
    val countHql: String?,
    val parameterBinders: List<ParameterBinder>,
    val returnType: QueryReturnType,
    val isAnnotatedQuery: Boolean = false,
    val isNativeQuery: Boolean = false,
    val isModifying: Boolean = false,
    val parameterStyle: ParameterStyle = ParameterStyle.NONE,
    val parameterNames: List<String> = emptyList(),
    val maxResults: Int? = null,
) {
    internal val annotatedParameters: QueryParameters by lazy(LazyThreadSafetyMode.PUBLICATION) {
        QueryParameterParser.parse(hql).toSpringParameters()
    }

    internal val countAnnotatedParameters: QueryParameters by lazy(LazyThreadSafetyMode.PUBLICATION) {
        countHql
            ?.let(QueryParameterParser::parse)
            ?.toSpringParameters()
            ?: QueryParameters(ParameterStyle.NONE)
    }

    /**
     * Resolves the query result class from the declared suspend return type.
     *
     * Single results use the return type itself; List, Page, and Slice use the element type.
     * Keeping this derived value out of the constructor preserves the public data-class ABI.
     */
    internal val resultClass: Class<*>? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        resolveResultClass(method, returnType)
    }
}

private fun resolveResultClass(method: Method, returnType: QueryReturnType): Class<*>? {
    if (returnType !in RESULT_BEARING_TYPES) return null

    val actualReturnType = extractSuspendReturnType(method) ?: return null
    val resultType = if (returnType == QueryReturnType.SINGLE) {
        actualReturnType
    } else {
        firstTypeArgument(actualReturnType) ?: return null
    }

    return rawClassOf(resultType)
}

private fun extractSuspendReturnType(method: Method): Type? {
    val continuationType = method.genericParameterTypes.lastOrNull() as? ParameterizedType ?: return null
    if (continuationType.rawType != Continuation::class.java) return null
    return continuationType.actualTypeArguments.firstOrNull()?.let(::unwrapWildcard)
}

private fun firstTypeArgument(type: Type): Type? =
    (type as? ParameterizedType)?.actualTypeArguments?.firstOrNull()?.let(::unwrapWildcard)

private fun rawClassOf(type: Type): Class<*>? = when (type) {
    is Class<*> -> type
    is ParameterizedType -> type.rawType as? Class<*>
    else -> null
}

private fun unwrapWildcard(type: Type): Type = when (type) {
    is WildcardType -> type.lowerBounds.firstOrNull() ?: type.upperBounds.firstOrNull() ?: type
    else -> type
}

private val RESULT_BEARING_TYPES = setOf(
    QueryReturnType.SINGLE,
    QueryReturnType.LIST,
    QueryReturnType.PAGE,
    QueryReturnType.SLICE,
)

internal data class QueryParameters(
    val style: ParameterStyle,
    val names: List<String> = emptyList(),
    val positions: List<Int> = emptyList(),
)

private fun io.clroot.hibernate.reactive.repository.query.QueryParameters.toSpringParameters(): QueryParameters =
    QueryParameters(
        style = when (style) {
            QueryParameterStyle.NAMED -> ParameterStyle.NAMED
            QueryParameterStyle.POSITIONAL -> ParameterStyle.POSITIONAL
            QueryParameterStyle.NONE -> ParameterStyle.NONE
        },
        names = names,
        positions = positions,
    )

internal fun PreparedQueryMethod.toRuntimeQuery(): PreparedRepositoryQuery = PreparedRepositoryQuery(
    methodName = method.name,
    hql = hql,
    countHql = countHql,
    parameterBindings = parameterBinders.map { binder ->
        when (binder) {
            ParameterBinder.Direct -> ParameterBinding.DIRECT
            ParameterBinder.Containing -> ParameterBinding.CONTAINING
            ParameterBinder.StartingWith -> ParameterBinding.STARTING_WITH
            ParameterBinder.EndingWith -> ParameterBinding.ENDING_WITH
            ParameterBinder.InCollection -> ParameterBinding.IN_COLLECTION
            ParameterBinder.NotInCollection -> ParameterBinding.NOT_IN_COLLECTION
        }
    },
    returnType = when (returnType) {
        QueryReturnType.SINGLE -> RepositoryQueryReturnType.SINGLE
        QueryReturnType.LIST -> RepositoryQueryReturnType.LIST
        QueryReturnType.BOOLEAN -> RepositoryQueryReturnType.BOOLEAN
        QueryReturnType.LONG -> RepositoryQueryReturnType.LONG
        QueryReturnType.VOID -> RepositoryQueryReturnType.VOID
        QueryReturnType.PAGE -> RepositoryQueryReturnType.PAGE
        QueryReturnType.SLICE -> RepositoryQueryReturnType.SLICE
        QueryReturnType.MODIFYING -> RepositoryQueryReturnType.MODIFYING
    },
    queryKind = if (isAnnotatedQuery) RepositoryQueryKind.ANNOTATED else RepositoryQueryKind.DERIVED,
    isNativeQuery = isNativeQuery,
    isModifying = isModifying,
    clearAutomatically = method.getAnnotation(Modifying::class.java)?.clearAutomatically == true,
    parameterStyle = when (parameterStyle) {
        ParameterStyle.NAMED -> QueryParameterStyle.NAMED
        ParameterStyle.POSITIONAL -> QueryParameterStyle.POSITIONAL
        ParameterStyle.NONE -> QueryParameterStyle.NONE
    },
    parameterNames = parameterNames,
    resultClass = resultClass,
    maxResults = maxResults,
    isDelete = partTree?.isDelete == true,
)
