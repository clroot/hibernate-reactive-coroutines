package io.clroot.hibernate.reactive.spring.boot.repository.query.condition

import io.clroot.hibernate.reactive.spring.boot.repository.query.ParameterBinder

/**
 * Builders for LIKE pattern conditions.
 */

internal data object LikeCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property LIKE :p$paramIndex",
        binders = listOf(ParameterBinder.Direct),
        paramCount = 1,
    )
}

internal data object NotLikeCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property NOT LIKE :p$paramIndex",
        binders = listOf(ParameterBinder.Direct),
        paramCount = 1,
    )
}

/**
 * Derived LIKE keywords escape `%` and `_` in user values because they add pattern wildcards.
 * Explicit `Like` accepts a user-supplied pattern and does not escape it.
 */

internal data object StartingWithCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property LIKE :p$paramIndex${ParameterBinder.LIKE_ESCAPE_CLAUSE}",
        binders = listOf(ParameterBinder.StartingWith),
        paramCount = 1,
    )
}

internal data object EndingWithCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property LIKE :p$paramIndex${ParameterBinder.LIKE_ESCAPE_CLAUSE}",
        binders = listOf(ParameterBinder.EndingWith),
        paramCount = 1,
    )
}

internal data object ContainingCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property LIKE :p$paramIndex${ParameterBinder.LIKE_ESCAPE_CLAUSE}",
        binders = listOf(ParameterBinder.Containing),
        paramCount = 1,
    )
}

internal data object NotContainingCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property NOT LIKE :p$paramIndex${ParameterBinder.LIKE_ESCAPE_CLAUSE}",
        binders = listOf(ParameterBinder.Containing),
        paramCount = 1,
    )
}
