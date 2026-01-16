package io.clroot.hibernate.reactive.spring.boot.repository.query.condition

import io.clroot.hibernate.reactive.spring.boot.repository.query.ParameterBinder

/**
 * 조건 생성 결과.
 *
 * @param condition 생성된 HQL 조건절
 * @param binders 이 조건에 필요한 파라미터 바인더들
 * @param paramCount 사용한 파라미터 개수
 */
internal data class ConditionResult(
    val condition: String,
    val binders: List<ParameterBinder>,
    val paramCount: Int,
)
