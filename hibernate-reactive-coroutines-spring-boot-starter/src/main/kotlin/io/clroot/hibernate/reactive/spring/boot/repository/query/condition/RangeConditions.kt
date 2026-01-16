package io.clroot.hibernate.reactive.spring.boot.repository.query.condition

import io.clroot.hibernate.reactive.spring.boot.repository.query.ParameterBinder

/**
 * 범위/컬렉션 조건 빌더들.
 */

/** BETWEEN :p0 AND :p1 */
internal data object BetweenCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property BETWEEN :p$paramIndex AND :p${paramIndex + 1}",
        binders = listOf(ParameterBinder.Direct, ParameterBinder.Direct),
        paramCount = 2,
    )
}

/** IN */
internal data object InCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property IN :p$paramIndex",
        binders = listOf(ParameterBinder.Direct),
        paramCount = 1,
    )
}

/** NOT IN */
internal data object NotInCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property NOT IN :p$paramIndex",
        binders = listOf(ParameterBinder.Direct),
        paramCount = 1,
    )
}
