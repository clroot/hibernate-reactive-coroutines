package io.clroot.hibernate.reactive.spring.boot.repository.query

/**
 * 파라미터 바인딩 스타일.
 */
enum class ParameterStyle {
    /** Named Parameter (:name) */
    NAMED,

    /** Positional Parameter (?1) */
    POSITIONAL,

    /** 파라미터 없음 */
    NONE,
}
