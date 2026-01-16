package io.clroot.hibernate.reactive.spring.boot.repository.query.condition

/**
 * Null 체크 및 Boolean 조건 빌더들.
 */

/** IS NULL */
internal data object IsNullCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property IS NULL",
        binders = emptyList(),
        paramCount = 0,
    )
}

/** IS NOT NULL */
internal data object IsNotNullCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property IS NOT NULL",
        binders = emptyList(),
        paramCount = 0,
    )
}

/** = TRUE */
internal data object TrueCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property = TRUE",
        binders = emptyList(),
        paramCount = 0,
    )
}

/** = FALSE */
internal data object FalseCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property = FALSE",
        binders = emptyList(),
        paramCount = 0,
    )
}

/** IS EMPTY (컬렉션) */
internal data object IsEmptyCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property IS EMPTY",
        binders = emptyList(),
        paramCount = 0,
    )
}

/** IS NOT EMPTY (컬렉션) */
internal data object IsNotEmptyCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property IS NOT EMPTY",
        binders = emptyList(),
        paramCount = 0,
    )
}
