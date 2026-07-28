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

/**
 * StartingWith/EndingWith/Containing은 사용자 값을 패턴에 끼워 넣으므로
 * 값 안의 `%`와 `_`를 이스케이프하고 `ESCAPE` 절을 함께 생성합니다.
 * (명시적 `Like` 키워드는 사용자가 직접 패턴을 넘기는 것이므로 이스케이프하지 않습니다.)
 */

/** LIKE with prefix: value% */
internal data object StartingWithCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property LIKE :p$paramIndex${ParameterBinder.LIKE_ESCAPE_CLAUSE}",
        binders = listOf(ParameterBinder.StartingWith),
        paramCount = 1,
    )
}

/** LIKE with suffix: %value */
internal data object EndingWithCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property LIKE :p$paramIndex${ParameterBinder.LIKE_ESCAPE_CLAUSE}",
        binders = listOf(ParameterBinder.EndingWith),
        paramCount = 1,
    )
}

/** LIKE with both: %value% */
internal data object ContainingCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property LIKE :p$paramIndex${ParameterBinder.LIKE_ESCAPE_CLAUSE}",
        binders = listOf(ParameterBinder.Containing),
        paramCount = 1,
    )
}

/** NOT LIKE with both: %value% */
internal data object NotContainingCondition : ConditionBuilder {
    override fun build(property: String, paramIndex: Int) = ConditionResult(
        condition = "$property NOT LIKE :p$paramIndex${ParameterBinder.LIKE_ESCAPE_CLAUSE}",
        binders = listOf(ParameterBinder.Containing),
        paramCount = 1,
    )
}
