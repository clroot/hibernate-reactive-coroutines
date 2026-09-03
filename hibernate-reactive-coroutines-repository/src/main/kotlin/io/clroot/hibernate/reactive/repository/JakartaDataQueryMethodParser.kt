package io.clroot.hibernate.reactive.repository

import io.clroot.hibernate.reactive.repository.query.CountQueryDeriver
import io.clroot.hibernate.reactive.repository.query.QueryParameterParser
import io.clroot.hibernate.reactive.repository.query.QueryParameterStyle
import io.clroot.hibernate.reactive.repository.query.QueryParameters
import io.clroot.hibernate.reactive.repository.query.QueryStatementType
import io.clroot.hibernate.reactive.repository.query.QueryOptions
import io.clroot.hibernate.reactive.repository.query.derived.DerivedQueryHqlCompiler
import io.clroot.hibernate.reactive.repository.query.derived.DerivedQueryParser
import io.clroot.hibernate.reactive.repository.query.derived.QuerySubject
import io.clroot.hibernate.reactive.repository.runtime.PreparedRepositoryQuery
import io.clroot.hibernate.reactive.repository.runtime.RepositoryQueryKind
import io.clroot.hibernate.reactive.repository.runtime.RepositoryQueryReturnType
import jakarta.data.Order
import jakarta.data.Sort
import jakarta.data.page.Page
import jakarta.data.page.PageRequest
import jakarta.data.repository.Param
import jakarta.data.repository.Query
import jakarta.persistence.Tuple
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Parameter
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import kotlin.coroutines.Continuation

/** Compiles Jakarta Data metadata and HRC method-name queries into neutral runtime descriptors. */
internal class JakartaDataQueryMethodParser(
    private val entityClass: Class<*>,
    private val entityName: String = entityClass.simpleName,
) {
    fun parseRepository(repositoryInterface: Class<*>): Map<String, PreparedRepositoryQuery> =
        repositoryInterface.methods
            .asSequence()
            .filter(::isCustomQueryMethod)
            .associate { method -> createMethodKey(method) to parse(method) }

    fun parse(method: Method): PreparedRepositoryQuery {
        val query = method.getAnnotation(Query::class.java)
        val options = method.getAnnotation(QueryOptions::class.java)
        require(query != null || options == null) {
            "@QueryOptions on method '${method.name}' requires Jakarta Data @Query"
        }
        return if (query == null) {
            parseDerivedQueryMethod(method)
        } else {
            parseAnnotatedQueryMethod(method, query, options)
        }
    }

    private fun isCustomQueryMethod(method: Method): Boolean =
        isDeclaredRepositoryMethod(method) && isSuspendMethod(method)

    private fun isDeclaredRepositoryMethod(method: Method): Boolean {
        if (method.name in BASE_METHODS) return false
        if (method.declaringClass == Any::class.java) return false
        if (method.isDefault || method.isSynthetic || method.isBridge) return false
        return !Modifier.isStatic(method.modifiers)
    }

    private fun isSuspendMethod(method: Method): Boolean =
        method.parameterTypes.lastOrNull()?.let(Continuation::class.java::isAssignableFrom) == true

    private fun createMethodKey(method: Method): String =
        "${method.name}#${method.parameterTypes.size - 1}"

    private fun parseAnnotatedQueryMethod(
        method: Method,
        annotation: Query,
        options: QueryOptions?,
    ): PreparedRepositoryQuery {
        val isNativeQuery = options?.nativeQuery == true
        val hql = normalizeQuery(annotation.value, isNativeQuery)
        val statementType = CountQueryDeriver.statementType(hql)
        require(statementType != QueryStatementType.UNKNOWN) {
            "Method '${method.name}' has an unsupported or unrecognized @Query statement"
        }

        val isModifying = statementType == QueryStatementType.MODIFYING
        val returnType = determineAnnotatedReturnType(method, isModifying)
        validateQueryOptions(method, options, isNativeQuery, isModifying, returnType)
        validatePageRequest(method, returnType)
        validateModifyingSpecialParameters(method, isModifying)

        val countHql = if (returnType == RepositoryQueryReturnType.PAGE) {
            when {
                options?.countQuery?.isNotBlank() == true -> options.countQuery.trim()
                isNativeQuery -> throw IllegalStateException(
                    "Native @Query method '${method.name}' returning Page requires @QueryOptions(countQuery = ...)",
                )
                else -> CountQueryDeriver.derive(hql)
            }
        } else {
            null
        }
        val queryParameters = QueryParameterParser.parse(hql)
        val countParameters = countHql?.let(QueryParameterParser::parse)
            ?: QueryParameters(QueryParameterStyle.NONE)
        val argumentNames = extractParameterNames(method)
        validateQueryParameters(method, queryParameters, countParameters, argumentNames)

        val prepared = PreparedRepositoryQuery(
            methodName = method.name,
            hql = hql,
            countHql = countHql,
            parameterBindings = emptyList(),
            returnType = returnType,
            queryKind = RepositoryQueryKind.ANNOTATED,
            isNativeQuery = isNativeQuery,
            isModifying = isModifying,
            clearAutomatically = options?.clearAutomatically == true,
            parameterStyle = queryParameters.style,
            parameterNames = argumentNames,
            resultClass = resolveResultClass(method, returnType),
        )
        validateAnnotatedResultType(method, prepared)
        return prepared
    }

    private fun normalizeQuery(value: String, isNativeQuery: Boolean): String {
        val query = value.trim()
        require(query.isNotEmpty()) { "@Query value must not be blank" }
        return if (!isNativeQuery && query.startsWith("where", ignoreCase = true)) {
            "FROM $entityName e $query"
        } else {
            query
        }
    }

    private fun validateQueryOptions(
        method: Method,
        options: QueryOptions?,
        isNativeQuery: Boolean,
        isModifying: Boolean,
        returnType: RepositoryQueryReturnType,
    ) {
        if (isNativeQuery && isModifying) {
            throw IllegalStateException(
                "Native update/delete @Query method '${method.name}' is not supported by Hibernate Reactive",
            )
        }
        if (options?.countQuery?.isNotBlank() == true && returnType != RepositoryQueryReturnType.PAGE) {
            throw IllegalStateException(
                "@QueryOptions(countQuery = ...) on method '${method.name}' requires a Page return type",
            )
        }
        if (options?.clearAutomatically == true && !isModifying) {
            throw IllegalStateException(
                "@QueryOptions(clearAutomatically = true) on method '${method.name}' requires update/delete @Query",
            )
        }
    }

    private fun determineAnnotatedReturnType(
        method: Method,
        isModifying: Boolean,
    ): RepositoryQueryReturnType = when {
        isModifying -> determineModifyingReturnType(method)
        isPageReturnType(method) -> RepositoryQueryReturnType.PAGE
        isListReturnType(method) -> RepositoryQueryReturnType.LIST
        else -> RepositoryQueryReturnType.SINGLE
    }

    private fun determineModifyingReturnType(method: Method): RepositoryQueryReturnType {
        val actualReturnType = extractSuspendReturnType(method)
        return when {
            actualReturnType != null && isAssignableToRawType(actualReturnType, Int::class.javaObjectType) ->
                RepositoryQueryReturnType.MODIFYING
            actualReturnType != null && isAssignableToRawType(actualReturnType, Long::class.javaObjectType) ->
                RepositoryQueryReturnType.LONG
            actualReturnType != null && isAssignableToRawType(actualReturnType, Unit::class.java) ->
                RepositoryQueryReturnType.VOID
            else -> throw IllegalStateException(
                "Update/delete @Query method '${method.name}' must return Int, Long, or Unit",
            )
        }
    }

    private fun parseDerivedQueryMethod(method: Method): PreparedRepositoryQuery {
        val derivedQuery = DerivedQueryParser.parse(method.name, entityClass)
        val returnType = determineDerivedReturnType(method, derivedQuery.subject)
        validatePageRequest(method, returnType)

        if (derivedQuery.limit != null && hasPageRequestParameter(method)) {
            throw IllegalStateException(
                "Method '${method.name}' combines a Top/First limit with a PageRequest parameter; " +
                    "use either the limit keyword or PageRequest, not both",
            )
        }

        val compiler = DerivedQueryHqlCompiler(entityName)
        val compiled = compiler.compile(derivedQuery)
        val argumentCount = queryArgumentCount(method)
        if (compiled.parameterBindings.size != argumentCount) {
            throw IllegalStateException(
                "Method '${method.name}' derives ${compiled.parameterBindings.size} query parameter(s) " +
                    "from its name but declares $argumentCount argument(s)",
            )
        }

        return PreparedRepositoryQuery(
            methodName = method.name,
            hql = compiled.hql,
            countHql = if (returnType == RepositoryQueryReturnType.PAGE) {
                compiler.compileCount(derivedQuery).hql
            } else {
                null
            },
            parameterBindings = compiled.parameterBindings,
            returnType = returnType,
            resultClass = resolveResultClass(method, returnType),
            maxResults = derivedQuery.limit,
            isDelete = derivedQuery.subject == QuerySubject.DELETE,
        )
    }

    private fun determineDerivedReturnType(
        method: Method,
        subject: QuerySubject,
    ): RepositoryQueryReturnType = when (subject) {
        QuerySubject.EXISTS -> RepositoryQueryReturnType.BOOLEAN
        QuerySubject.COUNT -> RepositoryQueryReturnType.LONG
        QuerySubject.DELETE -> determineDerivedDeleteReturnType(method)
        QuerySubject.FIND -> when {
            isPageReturnType(method) -> RepositoryQueryReturnType.PAGE
            isListReturnType(method) -> RepositoryQueryReturnType.LIST
            else -> RepositoryQueryReturnType.SINGLE
        }
    }

    private fun determineDerivedDeleteReturnType(method: Method): RepositoryQueryReturnType {
        val actualReturnType = extractSuspendReturnType(method) ?: return RepositoryQueryReturnType.VOID
        return when {
            isAssignableToRawType(actualReturnType, Int::class.javaObjectType) -> RepositoryQueryReturnType.MODIFYING
            isAssignableToRawType(actualReturnType, Long::class.javaObjectType) -> RepositoryQueryReturnType.LONG
            isAssignableToRawType(actualReturnType, Unit::class.java) -> RepositoryQueryReturnType.VOID
            else -> throw IllegalStateException(
                "Derived delete method '${method.name}' must return Unit, Int, or Long",
            )
        }
    }

    private fun validatePageRequest(method: Method, returnType: RepositoryQueryReturnType) {
        if (returnType == RepositoryQueryReturnType.PAGE && !hasPageRequestParameter(method)) {
            throw IllegalStateException("Method '${method.name}' returns Page but has no PageRequest parameter")
        }
    }

    private fun validateModifyingSpecialParameters(method: Method, isModifying: Boolean) {
        if (isModifying && method.parameters.dropLast(1).any { it.isJakartaDataSpecialParameter() }) {
            throw IllegalStateException("Update/delete @Query method '${method.name}' cannot use paging or sorting")
        }
    }

    private fun validateQueryParameters(
        method: Method,
        query: QueryParameters,
        count: QueryParameters,
        argumentNames: List<String>,
    ) {
        val duplicateName = argumentNames.groupingBy { it }.eachCount().entries
            .firstOrNull { it.value > 1 }
            ?.key
        if (duplicateName != null) {
            throw IllegalStateException("Method '${method.name}' declares duplicate query parameter name '$duplicateName'")
        }
        if (query.style != QueryParameterStyle.NONE && count.style != QueryParameterStyle.NONE &&
            query.style != count.style
        ) {
            throw IllegalStateException("Query and count query for method '${method.name}' use different parameter styles")
        }

        count.names.firstOrNull { it !in query.names }?.let { name ->
            throw IllegalStateException(
                "Count query for method '${method.name}' references parameter '$name' not used by the content query",
            )
        }
        count.positions.firstOrNull { it !in query.positions }?.let { position ->
            throw IllegalStateException(
                "Count query for method '${method.name}' references parameter ?$position not used by the content query",
            )
        }

        val unknownNames = (query.names + count.names).distinct() - argumentNames.toSet()
        if (unknownNames.isNotEmpty()) {
            throw IllegalStateException(
                "Query for method '${method.name}' references unknown parameter '${unknownNames.first()}'",
            )
        }
        val invalidPosition = (query.positions + count.positions).firstOrNull { it > argumentNames.size }
        if (invalidPosition != null) {
            throw IllegalStateException(
                "Query for method '${method.name}' references positional parameter ?$invalidPosition " +
                    "but has only ${argumentNames.size} query arguments",
            )
        }
    }

    private fun validateAnnotatedResultType(method: Method, prepared: PreparedRepositoryQuery) {
        if (prepared.isModifying) return
        val resultClass = prepared.resultClass
            ?: throw IllegalStateException("Could not determine the result type of @Query method '${method.name}'")
        if (resultClass.isAssignableFrom(entityClass)) return
        if (resultClass.isInterface) {
            throw IllegalStateException(
                "@Query method '${method.name}' declares unsupported interface projection ${resultClass.simpleName}; " +
                    "use a constructor DTO projection instead",
            )
        }
        if (resultClass.isArray || resultClass == Tuple::class.java) {
            throw IllegalStateException(
                "@Query method '${method.name}' declares unsupported Tuple/array projection ${resultClass.simpleName}",
            )
        }
    }

    private fun extractParameterNames(method: Method): List<String> = method.parameters
        .dropLast(1)
        .filterNot(Parameter::isJakartaDataSpecialParameter)
        .map { parameter ->
            parameter.getAnnotation(Param::class.java)?.value ?: parameter.name
        }

    private fun queryArgumentCount(method: Method): Int =
        method.parameters.dropLast(1).count { !it.isJakartaDataSpecialParameter() }

    private fun hasPageRequestParameter(method: Method): Boolean =
        method.parameterTypes.dropLast(1).any(PageRequest::class.java::isAssignableFrom)

    private fun isPageReturnType(method: Method): Boolean =
        extractSuspendReturnType(method)?.let { isAssignableToRawType(it, Page::class.java) } == true

    private fun isListReturnType(method: Method): Boolean = extractSuspendReturnType(method)?.let { type ->
        isAssignableToRawType(type, List::class.java) || isAssignableToRawType(type, Collection::class.java)
    } == true

    private fun resolveResultClass(
        method: Method,
        returnType: RepositoryQueryReturnType,
    ): Class<*>? {
        if (returnType !in RESULT_BEARING_TYPES) return null
        val actualReturnType = extractSuspendReturnType(method) ?: return null
        val resultType = if (returnType == RepositoryQueryReturnType.SINGLE) {
            actualReturnType
        } else {
            (actualReturnType as? ParameterizedType)?.actualTypeArguments?.firstOrNull()?.let(::unwrapWildcard)
                ?: return null
        }
        return rawClassOf(resultType)
    }

    private companion object {
        val BASE_METHODS = setOf(
            "save", "saveAll", "findById", "findAll", "findAllById", "existsById", "count",
            "deleteById", "delete", "deleteAllById", "deleteAll",
        )
        val RESULT_BEARING_TYPES = setOf(
            RepositoryQueryReturnType.SINGLE,
            RepositoryQueryReturnType.LIST,
            RepositoryQueryReturnType.PAGE,
        )
    }
}

private fun Parameter.isJakartaDataSpecialParameter(): Boolean =
    PageRequest::class.java.isAssignableFrom(type) ||
        Order::class.java.isAssignableFrom(type) ||
        Sort::class.java.isAssignableFrom(type) ||
        (type.isArray && Sort::class.java.isAssignableFrom(type.componentType))

private fun extractSuspendReturnType(method: Method): Type? {
    val continuation = method.genericParameterTypes.lastOrNull() as? ParameterizedType ?: return null
    if (continuation.rawType != Continuation::class.java) return null
    return continuation.actualTypeArguments.firstOrNull()?.let(::unwrapWildcard)
}

private fun unwrapWildcard(type: Type): Type = when (type) {
    is WildcardType -> type.lowerBounds.firstOrNull() ?: type.upperBounds.firstOrNull() ?: type
    else -> type
}

private fun rawClassOf(type: Type): Class<*>? = when (type) {
    is Class<*> -> type
    is ParameterizedType -> type.rawType as? Class<*>
    else -> null
}

private fun isAssignableToRawType(type: Type, targetClass: Class<*>): Boolean = when (type) {
    is Class<*> -> targetClass.isAssignableFrom(type)
    is ParameterizedType -> (type.rawType as? Class<*>)?.let(targetClass::isAssignableFrom) == true
    else -> false
}
