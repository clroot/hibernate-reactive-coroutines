package io.clroot.hibernate.reactive.spring.boot.repository.query.condition

import org.springframework.data.repository.query.parser.Part

/** Maps [Part.Type] values to [ConditionBuilder] implementations. */
internal object ConditionBuilderRegistry {
    private val builders: Map<Part.Type, ConditionBuilder> = mapOf(
        Part.Type.SIMPLE_PROPERTY to SimplePropertyCondition,
        Part.Type.NEGATING_SIMPLE_PROPERTY to NegatingSimplePropertyCondition,

        Part.Type.LIKE to LikeCondition,
        Part.Type.NOT_LIKE to NotLikeCondition,
        Part.Type.STARTING_WITH to StartingWithCondition,
        Part.Type.ENDING_WITH to EndingWithCondition,
        Part.Type.CONTAINING to ContainingCondition,
        Part.Type.NOT_CONTAINING to NotContainingCondition,

        Part.Type.LESS_THAN to LessThanCondition,
        Part.Type.LESS_THAN_EQUAL to LessThanEqualCondition,
        Part.Type.GREATER_THAN to GreaterThanCondition,
        Part.Type.GREATER_THAN_EQUAL to GreaterThanEqualCondition,
        Part.Type.BEFORE to LessThanCondition,   // BEFORE = LESS_THAN
        Part.Type.AFTER to GreaterThanCondition, // AFTER = GREATER_THAN

        Part.Type.BETWEEN to BetweenCondition,
        Part.Type.IN to InCondition,
        Part.Type.NOT_IN to NotInCondition,

        Part.Type.IS_NULL to IsNullCondition,
        Part.Type.IS_NOT_NULL to IsNotNullCondition,

        Part.Type.TRUE to TrueCondition,
        Part.Type.FALSE to FalseCondition,

        Part.Type.IS_EMPTY to IsEmptyCondition,
        Part.Type.IS_NOT_EMPTY to IsNotEmptyCondition,
    )
    private val unsupportedTypes = setOf(
        Part.Type.NEAR,
        Part.Type.WITHIN,
        Part.Type.REGEX,
        Part.Type.EXISTS,
    )

    /** Returns the builder for [type]. */
    fun get(type: Part.Type): ConditionBuilder {
        if (type in unsupportedTypes) {
            throw UnsupportedOperationException("Derived query type is not supported: ${type.name}")
        }
        return builders[type]
            ?: throw IllegalArgumentException("Unknown Part.Type: $type")
    }
}
