package io.clroot.hibernate.reactive.spring.boot.repository.query.condition

import io.clroot.hibernate.reactive.spring.boot.repository.query.ParameterBinder

/**
 * LIKE 패턴 조건 빌더들.
 */

/** LIKE */
internal data object LikeCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property LIKE :p$paramIndex",
        binders = listOf(ParameterBinder.Direct),
        paramCount = 1,
    )
}

/** NOT LIKE */
internal data object NotLikeCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property NOT LIKE :p$paramIndex",
        binders = listOf(ParameterBinder.Direct),
        paramCount = 1,
    )
}

/** LIKE with prefix: value% */
internal data object StartingWithCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property LIKE :p$paramIndex",
        binders = listOf(ParameterBinder.StartingWith),
        paramCount = 1,
    )
}

/** LIKE with suffix: %value */
internal data object EndingWithCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property LIKE :p$paramIndex",
        binders = listOf(ParameterBinder.EndingWith),
        paramCount = 1,
    )
}

/** LIKE with both: %value% */
internal data object ContainingCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property LIKE :p$paramIndex",
        binders = listOf(ParameterBinder.Containing),
        paramCount = 1,
    )
}

/** NOT LIKE with both: %value% */
internal data object NotContainingCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property NOT LIKE :p$paramIndex",
        binders = listOf(ParameterBinder.Containing),
        paramCount = 1,
    )
}
