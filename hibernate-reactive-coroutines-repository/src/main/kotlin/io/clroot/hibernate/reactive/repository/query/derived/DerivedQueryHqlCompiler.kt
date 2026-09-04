package io.clroot.hibernate.reactive.repository.query.derived

import io.clroot.hibernate.reactive.InternalHrcApi
import io.clroot.hibernate.reactive.repository.query.QueryPropertyPathValidator

/** Compiles a framework-neutral [DerivedQuery] into Hibernate Query Language. */
@InternalHrcApi
public class DerivedQueryHqlCompiler(
    private val entityName: String,
) {
    /**
     * Compile [query]. A non-empty [dynamicOrder] replaces ordering declared in the method name;
     * an empty or null list falls back to the declared ordering.
     */
    public fun compile(
        query: DerivedQuery,
        dynamicOrder: List<QueryOrder>? = null,
    ): CompiledQuery {
        val context = CompilationContext()
        val hql = when (query.subject) {
            QuerySubject.FIND -> buildSelectQuery(query, dynamicOrder, context)
            QuerySubject.COUNT -> buildCountQuery(query, context)
            QuerySubject.EXISTS -> buildExistsQuery(query, context)
            QuerySubject.DELETE -> buildDeleteQuery(query, context)
        }
        return CompiledQuery(hql, context.bindings.toList())
    }

    /** Compile the count query used to paginate the result of [query]. */
    public fun compileCount(query: DerivedQuery): CompiledQuery {
        val context = CompilationContext()
        return CompiledQuery(buildCountQuery(query, context), context.bindings.toList())
    }

    private fun buildSelectQuery(
        query: DerivedQuery,
        dynamicOrder: List<QueryOrder>?,
        context: CompilationContext,
    ): String {
        val where = buildWhereClause(query.predicate, context)
        val effectiveOrder = dynamicOrder?.takeIf(List<QueryOrder>::isNotEmpty) ?: query.orderBy
        val orderBy = buildOrderByClause(effectiveOrder)

        return buildString {
            if (query.distinct) append("SELECT DISTINCT e ")
            append("FROM $entityName e")
            if (where.isNotEmpty()) append(" WHERE $where")
            if (orderBy.isNotEmpty()) append(" ORDER BY $orderBy")
        }
    }

    private fun buildCountQuery(query: DerivedQuery, context: CompilationContext): String {
        val where = buildWhereClause(query.predicate, context)
        val countExpression = if (query.distinct) "COUNT(DISTINCT e)" else "COUNT(e)"
        return buildString {
            append("SELECT $countExpression FROM $entityName e")
            if (where.isNotEmpty()) append(" WHERE $where")
        }
    }

    private fun buildExistsQuery(query: DerivedQuery, context: CompilationContext): String {
        val where = buildWhereClause(query.predicate, context)
        return buildString {
            append("SELECT 1 FROM $entityName e")
            if (where.isNotEmpty()) append(" WHERE $where")
        }
    }

    private fun buildDeleteQuery(query: DerivedQuery, context: CompilationContext): String {
        val where = buildWhereClause(query.predicate, context)
        return buildString {
            append("FROM $entityName e")
            if (where.isNotEmpty()) append(" WHERE $where")
        }
    }

    private fun buildWhereClause(predicate: PredicateGroup, context: CompilationContext): String =
        predicate.disjuncts.joinToString(" OR ") { conjunction ->
            val conditions = conjunction.predicates.map { buildCondition(it, context) }
            if (conditions.size > 1) {
                "(${conditions.joinToString(" AND ")})"
            } else {
                conditions.joinToString(" AND ")
            }
        }

    private fun buildCondition(predicate: QueryPredicate, context: CompilationContext): String {
        if (predicate.operator in UNSUPPORTED_OPERATORS) {
            throw UnsupportedOperationException(
                "Derived query type is not supported: ${predicate.operator.name}",
            )
        }

        val ignoreCase = shouldIgnoreCase(predicate)
        val path = "e.${validatePropertyPath(predicate.property.value)}"
        val property = if (ignoreCase) "LOWER($path)" else path
        val parameterIndex = context.parameterIndex
        val condition = when (predicate.operator) {
            PredicateOperator.EQUALS -> "$property = :p$parameterIndex"
            PredicateOperator.NOT_EQUALS -> "$property <> :p$parameterIndex"
            PredicateOperator.LIKE -> "$property LIKE :p$parameterIndex"
            PredicateOperator.NOT_LIKE -> "$property NOT LIKE :p$parameterIndex"
            PredicateOperator.STARTING_WITH,
            PredicateOperator.ENDING_WITH,
            PredicateOperator.CONTAINING,
            -> "$property LIKE :p$parameterIndex$LIKE_ESCAPE_CLAUSE"
            PredicateOperator.NOT_CONTAINING -> "$property NOT LIKE :p$parameterIndex$LIKE_ESCAPE_CLAUSE"
            PredicateOperator.LESS_THAN,
            PredicateOperator.BEFORE,
            -> "$property < :p$parameterIndex"
            PredicateOperator.LESS_THAN_EQUAL -> "$property <= :p$parameterIndex"
            PredicateOperator.GREATER_THAN,
            PredicateOperator.AFTER,
            -> "$property > :p$parameterIndex"
            PredicateOperator.GREATER_THAN_EQUAL -> "$property >= :p$parameterIndex"
            PredicateOperator.BETWEEN -> "$property BETWEEN :p$parameterIndex AND :p${parameterIndex + 1}"
            PredicateOperator.IN -> "$property IN :p$parameterIndex"
            PredicateOperator.NOT_IN -> "$property NOT IN :p$parameterIndex"
            PredicateOperator.IS_NULL -> "$property IS NULL"
            PredicateOperator.IS_NOT_NULL -> "$property IS NOT NULL"
            PredicateOperator.TRUE -> "$property = TRUE"
            PredicateOperator.FALSE -> "$property = FALSE"
            PredicateOperator.IS_EMPTY -> "$property IS EMPTY"
            PredicateOperator.IS_NOT_EMPTY -> "$property IS NOT EMPTY"
            PredicateOperator.NEAR,
            PredicateOperator.WITHIN,
            PredicateOperator.REGEX,
            PredicateOperator.EXISTS,
            -> error("Unsupported operators are rejected above")
        }

        context.bindings += bindingsFor(predicate.operator)
        context.parameterIndex += predicate.operator.argumentCount

        if (!ignoreCase || predicate.operator.argumentCount == 0) return condition
        return condition.replace(PARAMETER_PLACEHOLDER) { "LOWER(${it.value})" }
    }

    private fun shouldIgnoreCase(predicate: QueryPredicate): Boolean {
        val isStringProperty = predicate.property.leafType == String::class.java
        return when (predicate.ignoreCase) {
            IgnoreCaseMode.ALWAYS -> {
                if (!isStringProperty) {
                    throw IllegalStateException(
                        "IgnoreCase cannot be applied to non-String property " +
                            "'${predicate.property.value}' of type ${predicate.property.leafType.name}",
                    )
                }
                true
            }
            IgnoreCaseMode.WHEN_POSSIBLE -> isStringProperty
            IgnoreCaseMode.NEVER -> false
        }
    }

    private fun bindingsFor(operator: PredicateOperator): List<ParameterBinding> = when (operator) {
        PredicateOperator.BETWEEN -> listOf(ParameterBinding.DIRECT, ParameterBinding.DIRECT)
        PredicateOperator.IN -> listOf(ParameterBinding.IN_COLLECTION)
        PredicateOperator.NOT_IN -> listOf(ParameterBinding.NOT_IN_COLLECTION)
        PredicateOperator.STARTING_WITH -> listOf(ParameterBinding.STARTING_WITH)
        PredicateOperator.ENDING_WITH -> listOf(ParameterBinding.ENDING_WITH)
        PredicateOperator.CONTAINING,
        PredicateOperator.NOT_CONTAINING,
        -> listOf(ParameterBinding.CONTAINING)
        PredicateOperator.IS_NULL,
        PredicateOperator.IS_NOT_NULL,
        PredicateOperator.TRUE,
        PredicateOperator.FALSE,
        PredicateOperator.IS_EMPTY,
        PredicateOperator.IS_NOT_EMPTY,
        PredicateOperator.EXISTS,
        -> emptyList()
        else -> listOf(ParameterBinding.DIRECT)
    }

    private fun buildOrderByClause(orders: List<QueryOrder>): String = orders.joinToString(", ") { order ->
        val property = validatePropertyPath(order.property)
        val expression = if (order.ignoreCase) "LOWER(e.$property)" else "e.$property"
        "$expression ${order.direction.name}"
    }

    private fun validatePropertyPath(property: String): String {
        require(QueryPropertyPathValidator.isSafe(property)) {
            "Invalid sort property"
        }
        return property
    }

    private class CompilationContext {
        var parameterIndex: Int = 0
        val bindings: MutableList<ParameterBinding> = mutableListOf()
    }

    private companion object {
        val PARAMETER_PLACEHOLDER: Regex = Regex(":p\\d+")
        const val LIKE_ESCAPE_CLAUSE: String = " ESCAPE '\\'"
        val UNSUPPORTED_OPERATORS: Set<PredicateOperator> = setOf(
            PredicateOperator.NEAR,
            PredicateOperator.WITHIN,
            PredicateOperator.REGEX,
            PredicateOperator.EXISTS,
        )
    }
}

/** HQL and parameter transformations produced by [DerivedQueryHqlCompiler]. */
@InternalHrcApi
public data class CompiledQuery(
    public val hql: String,
    public val parameterBindings: List<ParameterBinding>,
)

/** Framework-neutral transformation applied before a derived-query argument is bound. */
@InternalHrcApi
public enum class ParameterBinding {
    DIRECT,
    CONTAINING,
    STARTING_WITH,
    ENDING_WITH,
    IN_COLLECTION,
    NOT_IN_COLLECTION,
    ;

    /** Transform [value] into the value expected by the compiled HQL parameter. */
    public fun bind(value: Any?): Any? = when (this) {
        DIRECT -> value
        CONTAINING -> value?.let { "%${escapeLikeWildcards(it)}%" }
        STARTING_WITH -> value?.let { "${escapeLikeWildcards(it)}%" }
        ENDING_WITH -> value?.let { "%${escapeLikeWildcards(it)}" }
        IN_COLLECTION -> requireCollection(value, "IN")
        NOT_IN_COLLECTION -> requireCollection(value, "NOT IN")
    }

    private fun requireCollection(value: Any?, operator: String): Any {
        requireNotNull(value) { "$operator collection parameter must not be null" }
        require(value is Iterable<*> || value.javaClass.isArray) {
            "$operator parameter must be a collection or array"
        }
        return value
    }

    private fun escapeLikeWildcards(value: Any): String = value.toString()
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
}
