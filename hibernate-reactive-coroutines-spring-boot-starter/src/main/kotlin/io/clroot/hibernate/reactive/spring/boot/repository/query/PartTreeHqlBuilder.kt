package io.clroot.hibernate.reactive.spring.boot.repository.query

import io.clroot.hibernate.reactive.spring.boot.repository.query.condition.ConditionBuilderRegistry
import org.springframework.data.domain.Sort
import org.springframework.data.repository.query.parser.Part
import org.springframework.data.repository.query.parser.PartTree

/**
 * PartTree를 HQL 쿼리로 변환하는 빌더.
 *
 * Spring Data Commons의 PartTree를 순회하며 Hibernate HQL을 생성합니다.
 * 각 조건 타입의 HQL 생성은 [ConditionBuilderRegistry]에 위임합니다.
 */
class PartTreeHqlBuilder(
    private val entityName: String,
    private val partTree: PartTree,
) {

    private var parameterIndex = 0
    private val parameterBinders = mutableListOf<ParameterBinder>()

    /**
     * HQL 쿼리와 파라미터 바인더 목록을 생성합니다.
     */
    fun build(): HqlBuildResult {
        resetState()

        val hql = when {
            partTree.isCountProjection -> buildCountQuery()
            partTree.isExistsProjection -> buildExistsQuery()
            partTree.isDelete -> buildDeleteQuery()
            else -> buildSelectQuery()
        }

        return HqlBuildResult(hql, parameterBinders.toList())
    }

    /**
     * 동적 Sort를 적용하여 HQL을 생성합니다.
     * 동적 Sort가 있으면 우선 적용하고, 없으면 메서드명의 정렬을 사용합니다.
     */
    fun buildWithSort(dynamicSort: Sort?): HqlBuildResult {
        resetState()

        val hql = when {
            partTree.isCountProjection -> buildCountQuery()
            partTree.isExistsProjection -> buildExistsQuery()
            partTree.isDelete -> buildDeleteQuery()
            else -> buildSelectQueryWithSort(dynamicSort)
        }

        return HqlBuildResult(hql, parameterBinders.toList())
    }

    /**
     * Page 반환 타입을 위한 COUNT HQL을 생성합니다.
     */
    fun buildCountHql(): String {
        resetState()
        return buildCountQuery()
    }

    private fun resetState() {
        parameterIndex = 0
        parameterBinders.clear()
    }

    // ============================================
    // 쿼리 빌드 메서드들
    // ============================================

    private fun buildSelectQuery(): String = buildSelectQueryWithSort(null)

    private fun buildSelectQueryWithSort(dynamicSort: Sort?): String {
        val where = buildWhereClause()
        val effectiveSort = dynamicSort?.takeIf { it.isSorted } ?: partTree.sort
        val orderBy = buildOrderByClause(effectiveSort)

        return buildString {
            append("FROM $entityName e")
            if (where.isNotEmpty()) append(" WHERE $where")
            if (orderBy.isNotEmpty()) append(" ORDER BY $orderBy")
        }
    }

    private fun buildCountQuery(): String {
        val where = buildWhereClause()
        return buildString {
            append("SELECT COUNT(e) FROM $entityName e")
            if (where.isNotEmpty()) append(" WHERE $where")
        }
    }

    private fun buildExistsQuery(): String = buildCountQuery()

    private fun buildDeleteQuery(): String {
        val where = buildWhereClause()
        return buildString {
            append("DELETE FROM $entityName e")
            if (where.isNotEmpty()) append(" WHERE $where")
        }
    }

    // ============================================
    // WHERE 절 빌드
    // ============================================

    private fun buildWhereClause(): String {
        val orParts = partTree.toList()
        if (orParts.isEmpty()) return ""

        return orParts.joinToString(" OR ") { orPart ->
            val andConditions = orPart.map { part -> buildCondition(part) }.toList()
            if (andConditions.size > 1) {
                "(${andConditions.joinToString(" AND ")})"
            } else {
                andConditions.joinToString(" AND ")
            }
        }
    }

    /**
     * Part를 HQL 조건으로 변환합니다.
     * [ConditionBuilderRegistry]를 통해 적절한 빌더를 조회하여 위임합니다.
     */
    private fun buildCondition(part: Part): String {
        val property = "e.${part.property.segment}"
        val builder = ConditionBuilderRegistry.get(part.type)
        val result = builder.build(property, parameterIndex)

        parameterBinders.addAll(result.binders)
        parameterIndex += result.paramCount

        return result.condition
    }

    // ============================================
    // ORDER BY 절 빌드
    // ============================================

    private fun buildOrderByClause(sort: Sort): String {
        if (sort.isUnsorted) return ""
        return sort.map { order ->
            val direction = if (order.isAscending) "ASC" else "DESC"
            "e.${order.property} $direction"
        }.joinToString(", ")
    }
}

/**
 * HQL 빌드 결과.
 */
data class HqlBuildResult(
    val hql: String,
    val parameterBinders: List<ParameterBinder>,
)
