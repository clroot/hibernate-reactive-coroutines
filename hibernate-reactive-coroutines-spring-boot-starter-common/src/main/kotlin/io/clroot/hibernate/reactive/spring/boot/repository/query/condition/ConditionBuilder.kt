package io.clroot.hibernate.reactive.spring.boot.repository.query.condition

/**
 * Part.Type별 HQL 조건절 생성을 담당하는 빌더 인터페이스.
 *
 * 각 구현체는 특정 패턴의 조건을 생성하며,
 * [ConditionBuilderRegistry]를 통해 조회됩니다.
 */
internal sealed interface ConditionBuilder {
    /**
     * HQL 조건절을 생성합니다.
     *
     * @param property 엔티티 프로퍼티 (예: "e.name")
     * @param paramIndex 현재 파라미터 인덱스
     * @return 생성된 조건과 결과 정보
     */
    fun build(property: String, paramIndex: Int): ConditionResult
}
