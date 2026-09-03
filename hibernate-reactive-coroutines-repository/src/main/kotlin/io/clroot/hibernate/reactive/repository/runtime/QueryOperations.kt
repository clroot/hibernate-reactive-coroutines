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

/**
 * 쿼리 실행을 담당하는 내부 헬퍼 클래스.
 *
 * 메서드명 기반 파생 쿼리와 @Query 어노테이션 쿼리의 실행을 처리합니다.
 * 페이징 관련 쿼리는 PaginationOperations에서 처리합니다.
 *
 * @param T 엔티티 타입
 */
internal class QueryOperations<T : Any>(
    private val entityClass: Class<T>,
    private val sessionOperations: ReactiveSessionOperations,
    private val metamodel: Metamodel,
) {

    // ============================================
    // 파생 쿼리 실행
    // ============================================

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
     * 파생 `deleteBy...` 쿼리를 실행하고 삭제된 행 수를 반환합니다.
     *
     * 대상 엔티티를 먼저 로드한 뒤 제거하므로 cascade와 `@Version`이 정상 동작합니다.
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

    // ============================================
    // @Query 어노테이션 쿼리 실행
    // ============================================

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
     * @Query의 선언된 결과 클래스를 typed query에 사용할 형태로 반환합니다.
     * 엔티티의 상위 타입을 선언한 기존 메서드는 실제 엔티티 클래스를 유지합니다.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun annotatedResultClass(prepared: PreparedRepositoryQuery): Class<Any> {
        val declaredClass = checkNotNull(prepared.resultClass) {
            "Missing result type for @Query method '${prepared.methodName}'"
        }
        val queryClass = if (declaredClass.isAssignableFrom(entityClass)) entityClass else declaredClass
        return queryClass as Class<Any>
    }

    // ============================================
    // 파라미터 바인딩 헬퍼
    // ============================================

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

            QueryParameterStyle.NONE -> { /* 파라미터 없음 */
            }
        }
    }

    // ============================================
    // Sort 유틸리티
    // ============================================

    internal fun applyDynamicSort(hql: String, sort: List<QueryOrder>): String {
        if (sort.isEmpty()) return hql

        val baseHql = hql.replace(ORDER_BY_REGEX, "")
        val sortClause = buildSortClause(sort)

        return "$baseHql ORDER BY $sortClause"
    }

    /**
     * `@Query`로 선언된 쿼리에 동적 정렬을 적용합니다.
     *
     * 쿼리가 이미 `ORDER BY`를 갖고 있으면 뒤에 덧붙여 작성자의 정렬 의도를 보존합니다.
     * 안전하게 적용할 수 없으면 조용히 무시하지 않고 예외를 던집니다.
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
