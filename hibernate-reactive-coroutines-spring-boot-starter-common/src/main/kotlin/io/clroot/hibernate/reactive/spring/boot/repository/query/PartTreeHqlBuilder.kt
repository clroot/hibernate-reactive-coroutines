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
internal class PartTreeHqlBuilder(
    private val entityName: String,
    private val partTree: PartTree,
) {

    companion object {
        private val SAFE_PROPERTY_PATH = Regex("[\\p{L}_$][\\p{L}\\p{N}_$]*(\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)*")

        /** 이 빌더가 직접 생성하는 파라미터 플레이스홀더. 사용자 입력이 아니므로 치환이 안전합니다. */
        private val PARAMETER_PLACEHOLDER = Regex(":p\\d+")
    }

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
            if (partTree.isDistinct) append("SELECT DISTINCT e ")
            append("FROM $entityName e")
            if (where.isNotEmpty()) append(" WHERE $where")
            if (orderBy.isNotEmpty()) append(" ORDER BY $orderBy")
        }
    }

    private fun buildCountQuery(): String {
        val where = buildWhereClause()
        val countExpression = if (partTree.isDistinct) "COUNT(DISTINCT e)" else "COUNT(e)"
        return buildString {
            append("SELECT $countExpression FROM $entityName e")
            if (where.isNotEmpty()) append(" WHERE $where")
        }
    }

    private fun buildExistsQuery(): String {
        val where = buildWhereClause()
        return buildString {
            append("SELECT 1 FROM $entityName e")
            if (where.isNotEmpty()) append(" WHERE $where")
        }
    }

    /**
     * 파생 `deleteBy...` 메서드가 삭제할 엔티티를 조회하는 SELECT를 생성합니다.
     *
     * bulk `DELETE` 문은 cascade와 `@Version`을 건너뛰므로, Spring Data JPA와 동일하게
     * 대상을 로드한 뒤 하나씩 제거합니다.
     */
    private fun buildDeleteQuery(): String {
        val where = buildWhereClause()
        return buildString {
            append("FROM $entityName e")
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
        val propertyPath = "e.${part.property.toDotPath()}"
        val ignoreCase = shouldIgnoreCase(part)
        val builder = ConditionBuilderRegistry.get(part.type)
        val property = if (ignoreCase) "LOWER($propertyPath)" else propertyPath
        val result = builder.build(property, parameterIndex)

        parameterBinders.addAll(result.binders)
        parameterIndex += result.paramCount

        // 파라미터가 없는 조건(IS NULL 등)은 대소문자 구분이 의미 없습니다.
        if (!ignoreCase || result.paramCount == 0) return result.condition

        return result.condition.replace(PARAMETER_PLACEHOLDER) { "LOWER(${it.value})" }
    }

    /**
     * `IgnoreCase` 키워드를 적용할지 결정합니다.
     *
     * `IgnoreCase`(ALWAYS)를 String이 아닌 프로퍼티에 쓰면 시작 시점에 실패시키고,
     * `AllIgnoreCase`(WHEN_POSSIBLE)는 String 프로퍼티에만 적용합니다.
     */
    private fun shouldIgnoreCase(part: Part): Boolean {
        val isStringProperty = part.property.leafProperty.type == String::class.java

        return when (part.shouldIgnoreCase()) {
            Part.IgnoreCaseType.ALWAYS -> {
                if (!isStringProperty) {
                    throw IllegalStateException(
                        "IgnoreCase cannot be applied to non-String property " +
                                "'${part.property.toDotPath()}' of type ${part.property.leafProperty.type.name}",
                    )
                }
                true
            }

            Part.IgnoreCaseType.WHEN_POSSIBLE -> isStringProperty
            else -> false
        }
    }

    // ============================================
    // ORDER BY 절 빌드
    // ============================================

    private fun buildOrderByClause(sort: Sort): String {
        if (sort.isUnsorted) return ""
        return sort.map { order ->
            val direction = if (order.isAscending) "ASC" else "DESC"
            val property = order.property.also {
                require(SAFE_PROPERTY_PATH.matches(it) && "class" !in it.split('.')) {
                    "Invalid sort property"
                }
            }
            val expression = if (order.isIgnoreCase) "LOWER(e.$property)" else "e.$property"
            "$expression $direction"
        }.joinToString(", ")
    }
}

/**
 * HQL 빌드 결과.
 */
internal data class HqlBuildResult(
    val hql: String,
    val parameterBinders: List<ParameterBinder>,
)
