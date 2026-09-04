package io.clroot.hibernate.reactive.spring.boot.repository.query.condition

import io.clroot.hibernate.reactive.spring.boot.repository.query.ParameterBinder

/**
 * Result of building a query condition.
 *
 * @param condition Generated HQL condition clause.
 * @param binders Parameter binders required by the condition.
 * @param paramCount Number of parameters consumed.
 */
internal data class ConditionResult(
    val condition: String,
    val binders: List<ParameterBinder>,
    val paramCount: Int,
)
