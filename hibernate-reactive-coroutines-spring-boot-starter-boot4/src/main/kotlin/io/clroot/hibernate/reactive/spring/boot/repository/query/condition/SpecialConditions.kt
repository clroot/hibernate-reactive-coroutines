package io.clroot.hibernate.reactive.spring.boot.repository.query.condition

import io.clroot.hibernate.reactive.spring.boot.repository.query.ParameterBinder

/**
 * 특수 조건 빌더들.
 */

/** REGEX (HQL은 REGEX를 지원하지 않으므로 LIKE로 fallback) */
internal data object RegexCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property LIKE :p$paramIndex",
        binders = listOf(ParameterBinder.Direct),
        paramCount = 1,
    )
}

/** EXISTS */
internal data object ExistsCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "EXISTS :p$paramIndex",
        binders = listOf(ParameterBinder.Direct),
        paramCount = 1,
    )
}
