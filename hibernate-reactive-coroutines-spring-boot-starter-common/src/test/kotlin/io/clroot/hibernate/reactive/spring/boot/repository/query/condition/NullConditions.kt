package io.clroot.hibernate.reactive.spring.boot.repository.query.condition

/**
 * Builders for null and Boolean conditions.
 */

internal data object IsNullCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property IS NULL",
        binders = emptyList(),
        paramCount = 0,
    )
}

internal data object IsNotNullCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property IS NOT NULL",
        binders = emptyList(),
        paramCount = 0,
    )
}

internal data object TrueCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property = TRUE",
        binders = emptyList(),
        paramCount = 0,
    )
}

internal data object FalseCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property = FALSE",
        binders = emptyList(),
        paramCount = 0,
    )
}

internal data object IsEmptyCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property IS EMPTY",
        binders = emptyList(),
        paramCount = 0,
    )
}

internal data object IsNotEmptyCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property IS NOT EMPTY",
        binders = emptyList(),
        paramCount = 0,
    )
}
