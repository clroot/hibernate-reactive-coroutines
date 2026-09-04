package io.clroot.hibernate.reactive.repository.query

import io.clroot.hibernate.reactive.InternalHrcApi

/** Parameter syntax used by an explicit HQL or native query. */
@InternalHrcApi
public enum class QueryParameterStyle {
    NAMED,
    POSITIONAL,
    NONE,
}
