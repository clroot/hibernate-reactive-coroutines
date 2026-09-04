package io.clroot.hibernate.reactive.repository.query.derived

import io.clroot.hibernate.reactive.InternalHrcApi

/**
 * Framework-neutral representation of a query derived from a repository method name.
 */
@InternalHrcApi
public data class DerivedQuery(
    public val subject: QuerySubject,
    public val distinct: Boolean,
    public val predicate: PredicateGroup,
    public val orderBy: List<QueryOrder>,
    public val limit: Int?,
)

/** The operation selected by a derived-query method prefix. */
@InternalHrcApi
public enum class QuerySubject {
    FIND,
    COUNT,
    EXISTS,
    DELETE,
}

/**
 * A predicate in disjunctive normal form: the outer list is joined with OR and each
 * [Conjunction.predicates] list is joined with AND.
 */
@InternalHrcApi
public data class PredicateGroup(
    public val disjuncts: List<Conjunction>,
)

/** Predicates that must all match. */
@InternalHrcApi
public data class Conjunction(
    public val predicates: List<QueryPredicate>,
)

/** A single property condition. */
@InternalHrcApi
public data class QueryPredicate(
    public val property: PropertyPath,
    public val operator: PredicateOperator,
    public val ignoreCase: IgnoreCaseMode,
)

/** An entity property path resolved and validated against its owning type. */
@InternalHrcApi
public data class PropertyPath(
    public val value: String,
    public val leafType: Class<*>,
)

/** An order expression attached to a derived query or supplied by an adapter. */
@InternalHrcApi
public data class QueryOrder(
    public val property: String,
    public val direction: SortDirection = SortDirection.ASC,
    public val ignoreCase: Boolean = false,
)

/** Sort direction independent of any framework pagination API. */
@InternalHrcApi
public enum class SortDirection {
    ASC,
    DESC,
}

/** Case handling requested by a derived-query keyword. */
@InternalHrcApi
public enum class IgnoreCaseMode {
    NEVER,
    ALWAYS,
    WHEN_POSSIBLE,
}

/** Operators recognized in the predicate portion of a derived-query method name. */
@InternalHrcApi
public enum class PredicateOperator(public val argumentCount: Int) {
    EQUALS(1),
    NOT_EQUALS(1),
    LIKE(1),
    NOT_LIKE(1),
    STARTING_WITH(1),
    ENDING_WITH(1),
    CONTAINING(1),
    NOT_CONTAINING(1),
    LESS_THAN(1),
    LESS_THAN_EQUAL(1),
    GREATER_THAN(1),
    GREATER_THAN_EQUAL(1),
    BEFORE(1),
    AFTER(1),
    BETWEEN(2),
    IN(1),
    NOT_IN(1),
    IS_NULL(0),
    IS_NOT_NULL(0),
    TRUE(0),
    FALSE(0),
    IS_EMPTY(0),
    IS_NOT_EMPTY(0),
    NEAR(1),
    WITHIN(1),
    REGEX(1),
    EXISTS(0),
}
