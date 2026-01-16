package io.clroot.hibernate.reactive.spring.boot.repository.query.condition

import org.springframework.data.repository.query.parser.Part

/**
 * Part.Type을 ConditionBuilder에 매핑하는 레지스트리.
 *
 * 새로운 조건 타입을 추가하려면:
 * 1. ConditionBuilder 구현체를 생성
 * 2. [builders] 맵에 등록
 */
internal object ConditionBuilderRegistry {
    private val builders: Map<Part.Type, ConditionBuilder> = mapOf(
        // 단순 비교
        Part.Type.SIMPLE_PROPERTY to SimplePropertyCondition,
        Part.Type.NEGATING_SIMPLE_PROPERTY to NegatingSimplePropertyCondition,

        // LIKE 패턴
        Part.Type.LIKE to LikeCondition,
        Part.Type.NOT_LIKE to NotLikeCondition,
        Part.Type.STARTING_WITH to StartingWithCondition,
        Part.Type.ENDING_WITH to EndingWithCondition,
        Part.Type.CONTAINING to ContainingCondition,
        Part.Type.NOT_CONTAINING to NotContainingCondition,

        // 비교 연산자
        Part.Type.LESS_THAN to LessThanCondition,
        Part.Type.LESS_THAN_EQUAL to LessThanEqualCondition,
        Part.Type.GREATER_THAN to GreaterThanCondition,
        Part.Type.GREATER_THAN_EQUAL to GreaterThanEqualCondition,
        Part.Type.BEFORE to LessThanCondition,   // BEFORE = LESS_THAN
        Part.Type.AFTER to GreaterThanCondition, // AFTER = GREATER_THAN

        // 범위/컬렉션
        Part.Type.BETWEEN to BetweenCondition,
        Part.Type.IN to InCondition,
        Part.Type.NOT_IN to NotInCondition,

        // Null 체크
        Part.Type.IS_NULL to IsNullCondition,
        Part.Type.IS_NOT_NULL to IsNotNullCondition,

        // Boolean
        Part.Type.TRUE to TrueCondition,
        Part.Type.FALSE to FalseCondition,

        // 컬렉션 체크
        Part.Type.IS_EMPTY to IsEmptyCondition,
        Part.Type.IS_NOT_EMPTY to IsNotEmptyCondition,

        // 특수
        Part.Type.REGEX to RegexCondition,
        Part.Type.EXISTS to ExistsCondition,
    )

    /** 지원하지 않는 타입들 (Geospatial) */
    private val unsupportedTypes = setOf(Part.Type.NEAR, Part.Type.WITHIN)

    /**
     * Part.Type에 해당하는 ConditionBuilder를 반환합니다.
     *
     * @param type Part.Type
     * @return 해당 타입의 ConditionBuilder
     * @throws UnsupportedOperationException 지원하지 않는 타입인 경우
     * @throws IllegalArgumentException 알 수 없는 타입인 경우
     */
    fun get(type: Part.Type): ConditionBuilder {
        if (type in unsupportedTypes) {
            throw UnsupportedOperationException("Geospatial queries are not supported: $type")
        }
        return builders[type]
            ?: throw IllegalArgumentException("Unknown Part.Type: $type")
    }
}
