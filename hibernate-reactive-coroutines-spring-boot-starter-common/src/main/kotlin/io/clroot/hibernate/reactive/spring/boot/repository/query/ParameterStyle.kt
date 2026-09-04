package io.clroot.hibernate.reactive.spring.boot.repository.query

/** Query parameter binding style. */
internal enum class ParameterStyle {
    /** Named Parameter (:name) */
    NAMED,

    /** Positional Parameter (?1) */
    POSITIONAL,

    /** No parameters. */
    NONE,
}
