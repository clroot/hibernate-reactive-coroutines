package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.repository.query.CountQueryDeriver
import io.clroot.hibernate.reactive.repository.query.Modifying
import io.clroot.hibernate.reactive.repository.query.Param
import io.clroot.hibernate.reactive.repository.query.Query
import io.clroot.hibernate.reactive.repository.query.QueryParameterParser
import io.clroot.hibernate.reactive.repository.query.QueryParameters
import io.clroot.hibernate.reactive.repository.query.QueryParameterStyle
import io.clroot.hibernate.reactive.repository.query.QueryStatementType
import io.clroot.hibernate.reactive.repository.query.derived.DerivedQueryHqlCompiler
import io.clroot.hibernate.reactive.repository.query.derived.DerivedQueryParser
import io.clroot.hibernate.reactive.repository.query.derived.ParameterBinding
import io.clroot.hibernate.reactive.repository.query.derived.QuerySubject
import io.clroot.hibernate.reactive.spring.boot.repository.query.ParameterBinder
import io.clroot.hibernate.reactive.spring.boot.repository.query.ParameterStyle
import io.clroot.hibernate.reactive.spring.boot.repository.query.PreparedQueryMethod
import io.clroot.hibernate.reactive.spring.boot.repository.query.QueryReturnType
import jakarta.persistence.Tuple
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.domain.Sort
import org.springframework.data.repository.query.parser.PartTree
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import kotlin.coroutines.Continuation
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.kotlinFunction

/**
 * Parses repository query methods into [PreparedQueryMethod] instances.
 *
 * Supports both `@Query` methods and queries derived from method names.
 */
internal class QueryMethodParser(
    private val entityClass: Class<*>,
    private val entityName: String = entityClass.simpleName,
) {

    companion object {
        /** Names inherited from `CoroutineCrudRepository`, which are not custom queries. */
        private val BASE_METHODS = setOf(
            "save", "saveAll",
            "findById", "findAll", "findAllById",
            "existsById", "count",
            "deleteById", "delete", "deleteAllById", "deleteAll",
        )
    }

    fun parse(method: Method): PreparedQueryMethod {
        val queryAnnotation = method.getAnnotation(Query::class.java)
        require(queryAnnotation != null || !method.isAnnotationPresent(Modifying::class.java)) {
            "@Modifying on method '${method.name}' requires HRC @Query"
        }

        return if (queryAnnotation != null) {
            parseAnnotatedQueryMethod(method, queryAnnotation)
        } else {
            parseDerivedQueryMethod(method)
        }
    }

    fun isCustomQueryMethod(method: Method): Boolean =
        isDeclaredRepositoryMethod(method) && isSuspendMethod(method)

    /**
     * Identifies methods declared directly by the repository interface.
     *
     * Excludes inherited CRUD and `Object` methods plus compiler-generated and non-abstract methods.
     */
    fun isDeclaredRepositoryMethod(method: Method): Boolean {
        if (method.name in BASE_METHODS) return false
        if (method.declaringClass == Any::class.java) return false
        if (method.isDefault) return false
        if (method.isSynthetic || method.isBridge) return false
        if (Modifier.isStatic(method.modifiers)) return false
        return true
    }

    /**
     * Kotlin compiles suspend functions with a trailing [Continuation] parameter.
     */
    fun isSuspendMethod(method: Method): Boolean {
        val params = method.parameterTypes
        return params.isNotEmpty() && Continuation::class.java.isAssignableFrom(params.last())
    }

    /**
     * Distinguishes overloads by their name and declared argument count.
     */
    fun createMethodKey(method: Method): String {
        val paramCount = method.parameterTypes.size - 1
        return "${method.name}#$paramCount"
    }

    private fun parseAnnotatedQueryMethod(
        method: Method,
        queryAnnotation: Query,
    ): PreparedQueryMethod {
        validateQueryAnnotation(method, queryAnnotation)

        val hasPageable = hasPageableParameter(method)
        val isModifying = method.isAnnotationPresent(Modifying::class.java)

        val returnType = determineAnnotatedReturnType(method, isModifying)

        if ((returnType == QueryReturnType.PAGE || returnType == QueryReturnType.SLICE) && !hasPageable) {
            throw IllegalStateException(
                "Method '${method.name}' returns Page/Slice but has no Pageable parameter",
            )
        }

        val query = queryAnnotation.value
        val countHql = generateCountHqlIfNeeded(returnType, queryAnnotation, query)
        val queryParameters = QueryParameterParser.parse(query)
        val countParameters = countHql
            ?.let(QueryParameterParser::parse)
            ?: QueryParameters(QueryParameterStyle.NONE)
        val argumentNames = extractParameterNames(method)
        validateQueryParameters(method, queryParameters, countParameters, argumentNames)

        val prepared = PreparedQueryMethod(
            method = method,
            partTree = null,
            hql = query,
            countHql = countHql,
            parameterBinders = emptyList(),
            returnType = returnType,
            isAnnotatedQuery = true,
            isNativeQuery = queryAnnotation.nativeQuery,
            isModifying = isModifying,
            parameterStyle = queryParameters.style.toSpringParameterStyle(),
            parameterNames = argumentNames,
        )
        validateAnnotatedResultType(prepared)
        return prepared
    }

    private fun determineAnnotatedReturnType(method: Method, isModifying: Boolean): QueryReturnType {
        return when {
            isModifying -> determineModifyingReturnType(method)
            isPageReturnType(method) -> QueryReturnType.PAGE
            isSliceReturnType(method) -> QueryReturnType.SLICE
            isListReturnType(method) -> QueryReturnType.LIST
            else -> QueryReturnType.SINGLE
        }
    }

    private fun determineModifyingReturnType(method: Method): QueryReturnType {
        val actualReturnType = extractActualReturnType(method)
        return when {
            actualReturnType != null &&
                    isAssignableToRawType(actualReturnType, Int::class.javaObjectType) -> QueryReturnType.MODIFYING

            actualReturnType != null &&
                    isAssignableToRawType(actualReturnType, Unit::class.java) -> QueryReturnType.VOID

            else -> throw IllegalStateException(
                "@Modifying method '${method.name}' must return Int or Unit",
            )
        }
    }

    private fun generateCountHqlIfNeeded(
        returnType: QueryReturnType,
        queryAnnotation: Query,
        query: String,
    ): String? {
        if (returnType != QueryReturnType.PAGE) return null

        return when {
            queryAnnotation.countQuery.isNotBlank() -> queryAnnotation.countQuery
            queryAnnotation.nativeQuery -> throw IllegalStateException(
                "Native query with Page return type requires explicit countQuery",
            )

            else -> CountQueryDeriver.derive(query)
        }
    }

    private fun validateQueryAnnotation(method: Method, queryAnnotation: Query) {
        val query = queryAnnotation.value
        val isModifying = method.isAnnotationPresent(Modifying::class.java)
        val statementType = CountQueryDeriver.statementType(query)

        if (statementType == QueryStatementType.UNKNOWN) {
            throw IllegalStateException(
                "Method '${method.name}' has an unsupported or unrecognized @Query statement",
            )
        }
        if (isModifying && statementType == QueryStatementType.SELECT) {
            throw IllegalStateException(
                "@Modifying method '${method.name}' cannot have SELECT query",
            )
        }
        if (!isModifying && statementType == QueryStatementType.MODIFYING) {
            throw IllegalStateException(
                "Method '${method.name}' has UPDATE/DELETE query but missing @Modifying annotation",
            )
        }
    }

    private fun parseDerivedQueryMethod(method: Method): PreparedQueryMethod {
        val hasPageable = hasPageableParameter(method)

        // Parse with PartTree first to preserve Spring-facing validation exceptions and retain the
        // public compatibility value. Query generation uses only the neutral representation below.
        val partTree = PartTree(method.name, entityClass)
        val derivedQuery = DerivedQueryParser.parse(method.name, entityClass)
        val returnType = determineDerivedQueryReturnType(method, derivedQuery.subject)

        if ((returnType == QueryReturnType.PAGE || returnType == QueryReturnType.SLICE) && !hasPageable) {
            throw IllegalStateException(
                "Method '${method.name}' returns Page/Slice but has no Pageable parameter",
            )
        }

        val maxResults = derivedQuery.limit
        if (maxResults != null && hasPageable) {
            throw IllegalStateException(
                "Method '${method.name}' combines a Top/First limit with a Pageable parameter, " +
                        "which is ambiguous. Use either the limit keyword or Pageable, not both.",
            )
        }

        val compiler = DerivedQueryHqlCompiler(entityName)
        val compiled = compiler.compile(derivedQuery)
        val parameterBinders = compiled.parameterBindings.map(ParameterBinding::toSpringBinder)

        val declaredArgumentCount = queryArgumentCount(method)
        if (parameterBinders.size != declaredArgumentCount) {
            throw IllegalStateException(
                "Method '${method.name}' derives ${parameterBinders.size} query parameter(s) " +
                        "from its name but declares $declaredArgumentCount argument(s)",
            )
        }

        val countHql = if (returnType == QueryReturnType.PAGE) {
            compiler.compileCount(derivedQuery).hql
        } else {
            null
        }

        return PreparedQueryMethod(
            method = method,
            partTree = partTree,
            hql = compiled.hql,
            countHql = countHql,
            parameterBinders = parameterBinders,
            returnType = returnType,
            isAnnotatedQuery = false,
            isNativeQuery = false,
            isModifying = false,
            parameterStyle = ParameterStyle.NONE,
            parameterNames = emptyList(),
            maxResults = maxResults,
        )
    }

    private fun determineDerivedQueryReturnType(method: Method, subject: QuerySubject): QueryReturnType =
        when (subject) {
            QuerySubject.EXISTS -> QueryReturnType.BOOLEAN
            QuerySubject.COUNT -> QueryReturnType.LONG
            QuerySubject.DELETE -> determineDeleteReturnType(method)
            QuerySubject.FIND -> when {
                isPageReturnType(method) -> QueryReturnType.PAGE
                isSliceReturnType(method) -> QueryReturnType.SLICE
                isListReturnType(method) -> QueryReturnType.LIST
                else -> QueryReturnType.SINGLE
            }
        }

    /**
     * Determines the return type of a derived `deleteBy...` method.
     *
     * Matches Spring Data by supporting `Unit`, `Int`, and `Long`.
     */
    private fun determineDeleteReturnType(method: Method): QueryReturnType {
        val actualReturnType = extractActualReturnType(method) ?: return QueryReturnType.VOID
        return when {
            isAssignableToRawType(actualReturnType, Int::class.javaObjectType) -> QueryReturnType.MODIFYING
            isAssignableToRawType(actualReturnType, Long::class.javaObjectType) -> QueryReturnType.LONG
            isAssignableToRawType(actualReturnType, Unit::class.java) -> QueryReturnType.VOID
            else -> throw IllegalStateException(
                "Derived delete method '${method.name}' must return Unit, Int or Long",
            )
        }
    }

    private fun validateQueryParameters(
        method: Method,
        query: QueryParameters,
        count: QueryParameters,
        argumentNames: List<String>,
    ) {
        val duplicateName = argumentNames
            .groupingBy { it }
            .eachCount()
            .entries
            .firstOrNull { it.value > 1 }
            ?.key
        if (duplicateName != null) {
            throw IllegalStateException(
                "Method '${method.name}' declares duplicate query parameter name '$duplicateName'",
            )
        }

        if (query.style != QueryParameterStyle.NONE && count.style != QueryParameterStyle.NONE &&
            query.style != count.style
        ) {
            throw IllegalStateException(
                "Query and countQuery for method '${method.name}' use different parameter styles",
            )
        }

        val countOnlyName = count.names.firstOrNull { it !in query.names }
        if (countOnlyName != null) {
            throw IllegalStateException(
                "countQuery for method '${method.name}' references parameter '$countOnlyName' " +
                        "that is not used by the content query",
            )
        }
        val countOnlyPosition = count.positions.firstOrNull { it !in query.positions }
        if (countOnlyPosition != null) {
            throw IllegalStateException(
                "countQuery for method '${method.name}' references parameter ?$countOnlyPosition " +
                        "that is not used by the content query",
            )
        }

        val unknownNames = (query.names + count.names).distinct() - argumentNames.toSet()
        if (unknownNames.isNotEmpty()) {
            throw IllegalStateException(
                "Query for method '${method.name}' references unknown parameter '${unknownNames.first()}'",
            )
        }

        val argumentCount = argumentNames.size
        val invalidPosition = (query.positions + count.positions)
            .firstOrNull { it > argumentCount }
        if (invalidPosition != null) {
            throw IllegalStateException(
                "Query for method '${method.name}' references positional parameter ?$invalidPosition " +
                        "but has only $argumentCount query arguments",
            )
        }
    }

    /**
     * Excludes coroutine and pagination infrastructure parameters from query argument counts.
     */
    private fun queryArgumentCount(method: Method): Int =
        method.parameters.count { param ->
            param.type != Continuation::class.java &&
                    !Pageable::class.java.isAssignableFrom(param.type) &&
                    !Sort::class.java.isAssignableFrom(param.type)
        }

    /**
     * Rejects projections Hibernate cannot instantiate directly at startup.
     *
     * Scalar and constructor DTO projections supply the declared result class to typed queries.
     * Interface projections require proxy creation, while tuple and array projections require
     * additional mapping rules, neither of which this feature provides.
     */
    private fun validateAnnotatedResultType(prepared: PreparedQueryMethod) {
        if (prepared.returnType == QueryReturnType.MODIFYING || prepared.returnType == QueryReturnType.VOID) return

        val resultClass = prepared.resultClass ?: throw IllegalStateException(
            "Could not determine the result type of @Query method '${prepared.method.name}'",
        )
        if (resultClass.isAssignableFrom(entityClass)) return

        if (resultClass.isInterface) {
            throw IllegalStateException(
                "@Query method '${prepared.method.name}' declares interface projection " +
                        "${resultClass.simpleName}, which is not supported. Use a constructor DTO projection instead.",
            )
        }
        if (resultClass.isArray || resultClass == Tuple::class.java) {
            throw IllegalStateException(
                "@Query method '${prepared.method.name}' declares Tuple/array projection " +
                        "${resultClass.simpleName}, which is not supported. Use a scalar or constructor DTO projection instead.",
            )
        }
    }

    private fun extractParameterNames(method: Method): List<String> {
        val kotlinFunction = method.kotlinFunction
        val kotlinParams = kotlinFunction?.parameters
            ?.filter { it.kind == KParameter.Kind.VALUE }
            ?: emptyList()

        return method.parameters
            .mapIndexed { index, param -> param to kotlinParams.getOrNull(index)?.name }
            .filter { (param) -> param.type != Continuation::class.java }
            .filter { (param) -> !Pageable::class.java.isAssignableFrom(param.type) }
            .filter { (param) -> !Sort::class.java.isAssignableFrom(param.type) }
            .map { (param, kotlinName) ->
                param.getAnnotation(Param::class.java)?.value
                    ?: kotlinName
                    ?: param.name
            }
    }

    private fun hasPageableParameter(method: Method): Boolean {
        val params = method.parameterTypes
        val lastNonContinuationIndex = params.size - 2
        if (lastNonContinuationIndex < 0) return false
        return Pageable::class.java.isAssignableFrom(params[lastNonContinuationIndex])
    }

    private fun isPageReturnType(method: Method): Boolean {
        val actualReturnType = extractActualReturnType(method) ?: return false
        return isAssignableToRawType(actualReturnType, Page::class.java)
    }

    private fun isSliceReturnType(method: Method): Boolean {
        val actualReturnType = extractActualReturnType(method) ?: return false
        return isAssignableToRawType(actualReturnType, Slice::class.java) &&
                !isAssignableToRawType(actualReturnType, Page::class.java)
    }

    private fun isListReturnType(method: Method): Boolean {
        val actualReturnType = extractActualReturnType(method) ?: return false
        return isAssignableToRawType(actualReturnType, List::class.java) ||
                isAssignableToRawType(actualReturnType, Collection::class.java)
    }

    /**
     * Extracts a suspend function's logical return type from its compiled
     * `Continuation<? super T>` parameter.
     */
    private fun extractActualReturnType(method: Method): Type? {
        val genericParams = method.genericParameterTypes
        if (genericParams.isEmpty()) return null

        val lastParam = genericParams.last()
        if (lastParam !is ParameterizedType) return null
        if (lastParam.rawType != Continuation::class.java) return null

        val typeArg = lastParam.actualTypeArguments.firstOrNull() ?: return null
        return unwrapWildcard(typeArg)
    }

    private fun unwrapWildcard(type: Type): Type {
        return when (type) {
            is WildcardType -> {
                type.lowerBounds.firstOrNull() ?: type.upperBounds.firstOrNull() ?: type
            }

            else -> type
        }
    }

    private fun isAssignableToRawType(type: Type, targetClass: Class<*>): Boolean {
        return when (type) {
            is ParameterizedType -> {
                val rawType = type.rawType as? Class<*>
                rawType != null && targetClass.isAssignableFrom(rawType)
            }

            is Class<*> -> targetClass.isAssignableFrom(type)
            else -> false
        }
    }

}

private fun ParameterBinding.toSpringBinder(): ParameterBinder = when (this) {
    ParameterBinding.DIRECT -> ParameterBinder.Direct
    ParameterBinding.CONTAINING -> ParameterBinder.Containing
    ParameterBinding.STARTING_WITH -> ParameterBinder.StartingWith
    ParameterBinding.ENDING_WITH -> ParameterBinder.EndingWith
    ParameterBinding.IN_COLLECTION -> ParameterBinder.InCollection
    ParameterBinding.NOT_IN_COLLECTION -> ParameterBinder.NotInCollection
}

private fun QueryParameterStyle.toSpringParameterStyle(): ParameterStyle = when (this) {
    QueryParameterStyle.NAMED -> ParameterStyle.NAMED
    QueryParameterStyle.POSITIONAL -> ParameterStyle.POSITIONAL
    QueryParameterStyle.NONE -> ParameterStyle.NONE
}
