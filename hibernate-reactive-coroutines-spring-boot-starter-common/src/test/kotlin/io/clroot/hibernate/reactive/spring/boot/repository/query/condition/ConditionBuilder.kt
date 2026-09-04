package io.clroot.hibernate.reactive.spring.boot.repository.query.condition

/**
 * Builds HQL predicates for a [Part.Type].
 *
 * Implementations are retrieved through [ConditionBuilderRegistry].
 */
internal sealed interface ConditionBuilder {
    /**
     * Builds an HQL predicate.
     *
     * @param property entity property, such as `e.name`
     * @param paramIndex current parameter index
     * @return predicate and parameter metadata
     */
    fun build(property: String, paramIndex: Int): ConditionResult
}
