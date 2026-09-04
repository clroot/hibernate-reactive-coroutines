package io.clroot.hibernate.reactive.spring.boot.repository.query.condition

import io.clroot.hibernate.reactive.spring.boot.repository.query.ParameterBinder

/**
 * Builders for range and collection conditions.
 */

internal data object BetweenCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property BETWEEN :p$paramIndex AND :p${paramIndex + 1}",
        binders = listOf(ParameterBinder.Direct, ParameterBinder.Direct),
        paramCount = 2,
    )
}

internal data object InCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property IN :p$paramIndex",
        binders = listOf(ParameterBinder.InCollection),
        paramCount = 1,
    )
}

internal data object NotInCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property NOT IN :p$paramIndex",
        binders = listOf(ParameterBinder.NotInCollection),
        paramCount = 1,
    )
}
