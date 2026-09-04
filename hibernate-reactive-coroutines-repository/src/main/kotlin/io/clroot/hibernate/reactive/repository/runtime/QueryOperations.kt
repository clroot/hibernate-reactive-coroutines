package io.clroot.hibernate.reactive.repository.runtime

import io.clroot.hibernate.reactive.ReactiveSessionOperations
import io.clroot.hibernate.reactive.repository.query.CountQueryDeriver
import io.clroot.hibernate.reactive.repository.query.QueryAliasResolver
import io.clroot.hibernate.reactive.repository.query.QueryParameterStyle
import io.clroot.hibernate.reactive.repository.query.QueryPropertyPathValidator
import io.clroot.hibernate.reactive.repository.query.derived.QueryOrder
import jakarta.persistence.metamodel.Attribute
import jakarta.persistence.metamodel.ManagedType
import jakarta.persistence.metamodel.Metamodel
import jakarta.persistence.metamodel.PluralAttribute
import io.smallrye.mutiny.Uni
import jakarta.persistence.metamodel.SingularAttribute
import org.hibernate.reactive.mutiny.Mutiny

/** Executes derived and `@Query` repository queries; pagination is handled separately. */
internal class QueryOperations<T : Any>(
    private val entityClass: Class<T>,
    private val sessionOperations: ReactiveSessionOperations,
    private val metamodel: Metamodel,
) {

    suspend fun executeSingleQuery(hql: String, args: List<Any?>, maxResults: Int? = null): T? =
        sessionOperations.read { session ->
            val query = session.createQuery(hql, entityClass)
            bindIndexedParameters(query, args)
            maxResults?.let { query.maxResults = it }
            query.singleResultOrNull
        }

    suspend fun executeListQuery(hql: String, args: List<Any?>, maxResults: Int? = null): List<T> =
        sessionOperations.read { session ->
            val query = session.createQuery(hql, entityClass)
            bindIndexedParameters(query, args)
            maxResults?.let { query.maxResults = it }
            query.resultList
        }

    suspend fun executeExistsQuery(hql: String, args: List<Any?>): Boolean =
        sessionOperations.read { session ->
            val query = session.createQuery(hql, Int::class.javaObjectType)
            bindIndexedParameters(query, args)
            query.maxResults = 1
            query.resultList.map(List<Int>::isNotEmpty)
        }

    suspend fun executeCountQuery(hql: String, args: List<Any?>): Long =
        sessionOperations.read { session ->
            val query = session.createQuery(hql, Long::class.javaObjectType)
            bindIndexedParameters(query, args)
            query.singleResult
        } ?: 0L

    /**
     * Deletes entities matched by a derived `deleteBy...` query.
     *
     * Loading entities before removal preserves cascades and `@Version` optimistic locking.
     */
    suspend fun executeDeleteQuery(hql: String, args: List<Any?>): Long =
        sessionOperations.write { session ->
            val query = session.createQuery(hql, entityClass)
            bindIndexedParameters(query, args)
            query.resultList.chain { entities ->
                if (entities.isEmpty()) {
                    Uni.createFrom().item(0L)
                } else {
                    val managed: List<Any> = entities
                    session.removeAll(*managed.toTypedArray())
                        .replaceWith(entities.size.toLong())
                }
            }
        }

    suspend fun executeListQueryWithSort(
        prepared: PreparedRepositoryQuery,
        args: List<Any?>,
        sort: List<QueryOrder>,
    ): List<T> {
        val hql = applyDynamicSort(prepared.hql, sort)
        return executeListQuery(hql, args, prepared.maxResults)
    }

    suspend fun executeModifyingAnnotatedQuery(
        prepared: PreparedRepositoryQuery,
        args: List<Any?>,
    ): Int {
        if (prepared.isNativeQuery) {
            throw UnsupportedOperationException(
                "@Modifying with native query is not yet supported in Hibernate Reactive. Use JPQL instead.",
            )
        }

        return sessionOperations.write { session ->
            val query = session.createMutationQuery(prepared.hql)
            bindAnnotatedParameters(query, prepared, args)
            query.executeUpdate().map { affectedRows ->
                if (prepared.clearAutomatically) {
                    session.clear()
                }
                affectedRows
            }
        }
    }

    suspend fun executeListAnnotatedQuery(
        prepared: PreparedRepositoryQuery,
        args: List<Any?>,
        sort: List<QueryOrder> = emptyList(),
    ): List<Any> {
        val hql = applyAnnotatedQuerySort(prepared, sort)
        val resultClass = annotatedResultClass(prepared)

        return sessionOperations.read { session ->
            val query = if (prepared.isNativeQuery) {
                session.createNativeQuery(hql, resultClass)
            } else {
                session.createQuery(hql, resultClass)
            }

            bindAnnotatedParameters(query, prepared, args)
            query.resultList
        }
    }

    suspend fun executeSingleAnnotatedQuery(
        prepared: PreparedRepositoryQuery,
        args: List<Any?>,
    ): Any? {
        val resultClass = annotatedResultClass(prepared)

        return sessionOperations.read { session ->
            val query = if (prepared.isNativeQuery) {
                session.createNativeQuery(prepared.hql, resultClass)
            } else {
                session.createQuery(prepared.hql, resultClass)
            }

            bindAnnotatedParameters(query, prepared, args)
            query.singleResultOrNull
        }
    }

    /**
     * Returns the result class used to create the typed query.
     *
     * Keep the concrete entity class when a legacy method declares one of its supertypes.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun annotatedResultClass(prepared: PreparedRepositoryQuery): Class<Any> {
        val declaredClass = checkNotNull(prepared.resultClass) {
            "Missing result type for @Query method '${prepared.methodName}'"
        }
        val queryClass = if (declaredClass.isAssignableFrom(entityClass)) entityClass else declaredClass
        return queryClass as Class<Any>
    }

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
        prepared: PreparedRepositoryQuery,
        args: List<Any?>,
    ) {
        when (prepared.parameterStyle) {
            QueryParameterStyle.NAMED -> {
                prepared.parameters.names.forEach { name ->
                    query.setParameter(name, args[prepared.parameterNames.indexOf(name)])
                }
            }

            QueryParameterStyle.POSITIONAL -> {
                prepared.parameters.positions.forEach { position ->
                    query.setParameter(position, args[position - 1])
                }
            }

            QueryParameterStyle.NONE -> Unit
        }
    }

    internal fun <R> bindAnnotatedCountParameters(
        query: Mutiny.SelectionQuery<R>,
        prepared: PreparedRepositoryQuery,
        args: List<Any?>,
    ) {
        when (prepared.countParameters.style) {
            QueryParameterStyle.NAMED -> {
                prepared.countParameters.names.forEach { name ->
                    query.setParameter(name, args[prepared.parameterNames.indexOf(name)])
                }
            }

            QueryParameterStyle.POSITIONAL -> {
                prepared.countParameters.positions.forEach { position ->
                    query.setParameter(position, args[position - 1])
                }
            }

            QueryParameterStyle.NONE -> Unit
        }
    }

    private fun bindAnnotatedParameters(
        query: Mutiny.MutationQuery,
        prepared: PreparedRepositoryQuery,
        args: List<Any?>,
    ) {
        when (prepared.parameterStyle) {
            QueryParameterStyle.NAMED -> {
                prepared.parameters.names.forEach { name ->
                    query.setParameter(name, args[prepared.parameterNames.indexOf(name)])
                }
            }

            QueryParameterStyle.POSITIONAL -> {
                prepared.parameters.positions.forEach { position ->
                    query.setParameter(position, args[position - 1])
                }
            }

            QueryParameterStyle.NONE -> {
            }
        }
    }

    internal fun applyDynamicSort(hql: String, sort: List<QueryOrder>): String {
        if (sort.isEmpty()) return hql

        val baseHql = hql.replace(ORDER_BY_REGEX, "")
        val sortClause = buildSortClause(sort)

        return "$baseHql ORDER BY $sortClause"
    }

    /**
     * Appends dynamic sorting to an `@Query`.
     *
     * Existing ordering remains first to preserve the query author's precedence. Ambiguous aliases
     * are rejected rather than risking an invalid or semantically different query.
     */
    internal fun applyAnnotatedQuerySort(
        prepared: PreparedRepositoryQuery,
        sort: List<QueryOrder>,
    ): String {
        if (sort.isEmpty()) return prepared.hql

        if (prepared.isNativeQuery) {
            throw UnsupportedOperationException(
                "Sorting a native @Query is not supported for method '${prepared.methodName}'. " +
                        "Declare the ORDER BY clause inside the query instead.",
            )
        }

        val alias = QueryAliasResolver.resolve(prepared.hql)
            ?: throw IllegalStateException(
                "Cannot apply Sort to @Query method '${prepared.methodName}' because its root alias " +
                        "could not be determined. Declare the ORDER BY clause inside the query instead.",
            )

        val sortClause = buildSortClause(sort, alias)
        val baseHql = prepared.hql.trimEnd()

        return if (CountQueryDeriver.hasOrderBy(baseHql)) {
            "$baseHql, $sortClause"
        } else {
            "$baseHql ORDER BY $sortClause"
        }
    }

    internal fun buildSortClause(sort: List<QueryOrder>, alias: String = "e"): String {
        if (sort.isEmpty()) return ""
        return sort.map { order ->
            val direction = order.direction.name
            val segments = order.property.split('.')
            require(QueryPropertyPathValidator.isSafe(order.property)) {
                "Invalid sort property"
            }

            var owningType: ManagedType<*> = managedType(entityClass)
            var leafType: Class<*>? = null
            segments.forEachIndexed { index, segment ->
                val attribute = try {
                    owningType.getAttribute(segment)
                } catch (_: IllegalArgumentException) {
                    throw IllegalArgumentException("Unknown sort property")
                }

                if (attribute is PluralAttribute<*, *, *>) {
                    throw IllegalArgumentException("Unsupported plural sort property")
                }
                if (attribute !is SingularAttribute<*, *>) {
                    throw IllegalArgumentException("Unknown sort property")
                }

                if (index < segments.lastIndex) {
                    owningType = managedType(attribute.type.javaType)
                } else {
                    if (attribute.persistentAttributeType != Attribute.PersistentAttributeType.BASIC) {
                        throw IllegalArgumentException("Sort property must resolve to a basic attribute")
                    }
                    leafType = attribute.javaType
                }
            }
            val expression = if (order.ignoreCase) {
                require(leafType == String::class.java) {
                    "Ignore-case sorting requires a String property"
                }
                "LOWER($alias.${order.property})"
            } else {
                "$alias.${order.property}"
            }
            "$expression $direction"
        }.joinToString(", ")
    }

    private fun managedType(javaType: Class<*>): ManagedType<*> = try {
        metamodel.managedType(javaType)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Unknown sort property")
    }
}

private val ORDER_BY_REGEX = Regex(" ORDER BY .+$", RegexOption.IGNORE_CASE)
