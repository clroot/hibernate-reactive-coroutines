package io.clroot.hibernate.reactive.spring.boot.repository.query.condition

import io.clroot.hibernate.reactive.spring.boot.repository.query.ParameterBinder

/**
 * 단순 비교 조건 빌더들.
 */

/** 단순 비교: = */
internal data object SimplePropertyCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property = :p$paramIndex",
        binders = listOf(ParameterBinder.Direct),
        paramCount = 1,
    )
}

/** 부정 비교: <> */
internal data object NegatingSimplePropertyCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property <> :p$paramIndex",
        binders = listOf(ParameterBinder.Direct),
        paramCount = 1,
    )
}

/** < */
internal data object LessThanCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property < :p$paramIndex",
        binders = listOf(ParameterBinder.Direct),
        paramCount = 1,
    )
}

/** <= */
internal data object LessThanEqualCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property <= :p$paramIndex",
        binders = listOf(ParameterBinder.Direct),
        paramCount = 1,
    )
}

/** > */
internal data object GreaterThanCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property > :p$paramIndex",
        binders = listOf(ParameterBinder.Direct),
        paramCount = 1,
    )
}

/** >= */
internal data object GreaterThanEqualCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property >= :p$paramIndex",
        binders = listOf(ParameterBinder.Direct),
        paramCount = 1,
    )
}
