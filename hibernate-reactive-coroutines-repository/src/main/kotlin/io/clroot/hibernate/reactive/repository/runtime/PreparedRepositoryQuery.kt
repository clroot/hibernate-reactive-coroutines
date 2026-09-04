package io.clroot.hibernate.reactive.repository.runtime

import io.clroot.hibernate.reactive.InternalHrcApi
import io.clroot.hibernate.reactive.repository.query.QueryParameterParser
import io.clroot.hibernate.reactive.repository.query.QueryParameterStyle
import io.clroot.hibernate.reactive.repository.query.QueryParameters
import io.clroot.hibernate.reactive.repository.query.derived.ParameterBinding

/** Framework-neutral execution metadata prepared by a repository integration at startup. */
@InternalHrcApi
public data class PreparedRepositoryQuery(
    public val methodName: String,
    public val hql: String,
    public val countHql: String?,
    public val parameterBindings: List<ParameterBinding>,
    public val returnType: RepositoryQueryReturnType,
    public val queryKind: RepositoryQueryKind = RepositoryQueryKind.DERIVED,
    public val isNativeQuery: Boolean = false,
    public val isModifying: Boolean = false,
    public val clearAutomatically: Boolean = false,
    public val parameterStyle: QueryParameterStyle = QueryParameterStyle.NONE,
    public val parameterNames: List<String> = emptyList(),
    public val resultClass: Class<*>? = null,
    public val maxResults: Int? = null,
    public val isDelete: Boolean = false,
) {
    internal val parameters: QueryParameters by lazy(LazyThreadSafetyMode.PUBLICATION) {
        QueryParameterParser.parse(hql)
    }

    internal val countParameters: QueryParameters by lazy(LazyThreadSafetyMode.PUBLICATION) {
        countHql?.let(QueryParameterParser::parse) ?: QueryParameters(QueryParameterStyle.NONE)
    }
}

/** Identifies whether query text came from method-name derivation or an integration annotation. */
@InternalHrcApi
public enum class RepositoryQueryKind {
    DERIVED,
    ANNOTATED,
}

/** Result shape understood by the framework-neutral repository runtime. */
@InternalHrcApi
public enum class RepositoryQueryReturnType {
    SINGLE,
    LIST,
    BOOLEAN,
    LONG,
    VOID,
    PAGE,
    SLICE,
    MODIFYING,
}
