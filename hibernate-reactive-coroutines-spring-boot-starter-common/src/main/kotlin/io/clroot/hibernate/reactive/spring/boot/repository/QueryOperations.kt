package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.spring.boot.repository.query.CountQueryDeriver
import io.clroot.hibernate.reactive.spring.boot.repository.query.Modifying
import io.clroot.hibernate.reactive.spring.boot.repository.query.ParameterStyle
import io.clroot.hibernate.reactive.spring.boot.repository.query.QueryAliasResolver
import io.clroot.hibernate.reactive.spring.boot.repository.query.PreparedQueryMethod
import io.clroot.hibernate.reactive.spring.boot.repository.query.QueryConstants.ORDER_BY_REGEX
import io.clroot.hibernate.reactive.spring.boot.repository.query.QueryReturnType
import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import jakarta.persistence.metamodel.Attribute
import jakarta.persistence.metamodel.ManagedType
import jakarta.persistence.metamodel.Metamodel
import jakarta.persistence.metamodel.PluralAttribute
import io.smallrye.mutiny.Uni
import jakarta.persistence.metamodel.SingularAttribute
import org.hibernate.reactive.mutiny.Mutiny
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort

/**
 * 쿼리 실행을 담당하는 내부 헬퍼 클래스.
 *
 * PartTree 기반 쿼리와 @Query 어노테이션 쿼리의 실행을 처리합니다.
 * 페이징 관련 쿼리는 PaginationOperations에서 처리합니다.
 *
 * @param T 엔티티 타입
 */
internal class QueryOperations<T : Any>(
    private val entityClass: Class<T>,
    private val sessionProvider: TransactionalAwareSessionProvider,
    private val metamodel: Metamodel? = null,
) {
    companion object {
        private val VALID_SORT_PATH = Regex("[\\p{L}_$][\\p{L}\\p{N}_$]*(\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)*")
    }

    // ============================================
    // PartTree 쿼리 실행
    // ============================================

    suspend fun executeSingleQuery(hql: String, args: List<Any?>, maxResults: Int? = null): T? =
        sessionProvider.read { session ->
            val query = session.createQuery(hql, entityClass)
            bindIndexedParameters(query, args)
            maxResults?.let { query.maxResults = it }
            query.singleResultOrNull
        }

    suspend fun executeListQuery(hql: String, args: List<Any?>, maxResults: Int? = null): List<T> =
        sessionProvider.read { session ->
            val query = session.createQuery(hql, entityClass)
            bindIndexedParameters(query, args)
            maxResults?.let { query.maxResults = it }
            query.resultList
        }

    suspend fun executeExistsQuery(hql: String, args: List<Any?>): Boolean {
        val count = sessionProvider.read { session ->
            val query = session.createQuery(hql, Long::class.javaObjectType)
            bindIndexedParameters(query, args)
            query.singleResult
        }
        return (count ?: 0L) > 0
    }

    suspend fun executeCountQuery(hql: String, args: List<Any?>): Long =
        sessionProvider.read { session ->
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
        sessionProvider.write { session ->
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
        prepared: PreparedQueryMethod,
        args: List<Any?>,
        sort: Sort,
    ): List<T> {
        val hql = applyDynamicSort(prepared.hql, sort)
        return executeListQuery(hql, args, prepared.maxResults)
    }

    // ============================================
    // @Query 어노테이션 쿼리 실행
    // ============================================

    suspend fun executeModifyingAnnotatedQuery(
        prepared: PreparedQueryMethod,
        args: List<Any?>,
    ): Int {
        if (prepared.isNativeQuery) {
            throw UnsupportedOperationException(
                "@Modifying with native query is not yet supported in Hibernate Reactive. Use JPQL instead.",
            )
        }

        val clearAutomatically = prepared.method
            .getAnnotation(Modifying::class.java)
            ?.clearAutomatically == true

        return sessionProvider.write { session ->
            val query = session.createMutationQuery(prepared.hql)
            bindAnnotatedParameters(query, prepared, args)
            query.executeUpdate().map { affectedRows ->
                if (clearAutomatically) {
                    session.clear()
                }
                affectedRows
            }
        }
    }

    suspend fun executeListAnnotatedQuery(
        prepared: PreparedQueryMethod,
        args: List<Any?>,
        sort: Sort = Sort.unsorted(),
    ): List<T> {
        val hql = applyAnnotatedQuerySort(prepared, sort)

        return sessionProvider.read { session ->
            val query = if (prepared.isNativeQuery) {
                session.createNativeQuery(hql, entityClass)
            } else {
                session.createQuery(hql, entityClass)
            }

            bindAnnotatedParameters(query, prepared, args)
            query.resultList
        }
    }

    suspend fun executeSingleAnnotatedQuery(
        prepared: PreparedQueryMethod,
        args: List<Any?>,
    ): T? {
        return sessionProvider.read { session ->
            val query = if (prepared.isNativeQuery) {
                session.createNativeQuery(prepared.hql, entityClass)
            } else {
                session.createQuery(prepared.hql, entityClass)
            }

            bindAnnotatedParameters(query, prepared, args)
            query.singleResultOrNull
        }
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
        prepared: PreparedQueryMethod,
        args: List<Any?>,
    ) {
        when (prepared.parameterStyle) {
            ParameterStyle.NAMED -> {
                prepared.annotatedParameters.names.forEach { name ->
                    query.setParameter(name, args[prepared.parameterNames.indexOf(name)])
                }
            }

            ParameterStyle.POSITIONAL -> {
                prepared.annotatedParameters.positions.forEach { position ->
                    query.setParameter(position, args[position - 1])
                }
            }

            ParameterStyle.NONE -> Unit
        }
    }

    internal fun <R> bindAnnotatedCountParameters(
        query: Mutiny.SelectionQuery<R>,
        prepared: PreparedQueryMethod,
        args: List<Any?>,
    ) {
        when (prepared.countAnnotatedParameters.style) {
            ParameterStyle.NAMED -> {
                prepared.countAnnotatedParameters.names.forEach { name ->
                    query.setParameter(name, args[prepared.parameterNames.indexOf(name)])
                }
            }

            ParameterStyle.POSITIONAL -> {
                prepared.countAnnotatedParameters.positions.forEach { position ->
                    query.setParameter(position, args[position - 1])
                }
            }

            ParameterStyle.NONE -> Unit
        }
    }

    private fun bindAnnotatedParameters(
        query: Mutiny.MutationQuery,
        prepared: PreparedQueryMethod,
        args: List<Any?>,
    ) {
        when (prepared.parameterStyle) {
            ParameterStyle.NAMED -> {
                prepared.annotatedParameters.names.forEach { name ->
                    query.setParameter(name, args[prepared.parameterNames.indexOf(name)])
                }
            }

            ParameterStyle.POSITIONAL -> {
                prepared.annotatedParameters.positions.forEach { position ->
                    query.setParameter(position, args[position - 1])
                }
            }

            ParameterStyle.NONE -> { /* 파라미터 없음 */
            }
        }
    }

    // ============================================
    // Sort 유틸리티
    // ============================================

    internal fun applyDynamicSort(hql: String, sort: Sort): String {
        if (sort.isUnsorted) return hql

        val baseHql = hql.replace(ORDER_BY_REGEX, "")
        val sortClause = buildSortClause(sort)

        return "$baseHql ORDER BY $sortClause"
    }

    /**
     * `@Query`로 선언된 쿼리에 동적 [Sort]를 적용합니다.
     *
     * 쿼리가 이미 `ORDER BY`를 갖고 있으면 뒤에 덧붙여 작성자의 정렬 의도를 보존합니다.
     * 안전하게 적용할 수 없으면 조용히 무시하지 않고 예외를 던집니다.
     */
    internal fun applyAnnotatedQuerySort(prepared: PreparedQueryMethod, sort: Sort): String {
        if (sort.isUnsorted) return prepared.hql

        if (prepared.isNativeQuery) {
            throw UnsupportedOperationException(
                "Sorting a native @Query is not supported for method '${prepared.method.name}'. " +
                        "Declare the ORDER BY clause inside the query instead.",
            )
        }

        val alias = QueryAliasResolver.resolve(prepared.hql)
            ?: throw IllegalStateException(
                "Cannot apply Sort to @Query method '${prepared.method.name}' because its root alias " +
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

    internal fun buildSortClause(sort: Sort, alias: String = "e"): String {
        if (sort.isUnsorted) return ""
        return sort.map { order ->
            val direction = if (order.isAscending) "ASC" else "DESC"
            val segments = order.property.split('.')
            require(VALID_SORT_PATH.matches(order.property) && "class" !in segments) {
                "Invalid sort property"
            }

            var owningType: ManagedType<*> = managedType(entityClass)
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
                } else if (attribute.persistentAttributeType != Attribute.PersistentAttributeType.BASIC) {
                    throw IllegalArgumentException("Sort property must resolve to a basic attribute")
                }
            }
            "$alias.${order.property} $direction"
        }.joinToString(", ")
    }

    private fun managedType(javaType: Class<*>): ManagedType<*> = try {
        (metamodel ?: sessionProvider.metamodel).managedType(javaType)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Unknown sort property")
    }
}
