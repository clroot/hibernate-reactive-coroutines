package io.clroot.hibernate.reactive.repository.query

/** Parameter syntax used by an explicit HQL or native query. */
public enum class QueryParameterStyle {
    NAMED,
    POSITIONAL,
    NONE,
}
