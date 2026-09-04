package io.clroot.hibernate.reactive.spring.boot.repository.query

/** Query method return types. */
public enum class QueryReturnType {
    /** A nullable single entity. */
    SINGLE,

    /** A list of entities. */
    LIST,

    /** Boolean (existsBy) */
    BOOLEAN,

    /** Long (countBy) */
    LONG,

    /** Unit/Void (deleteBy) */
    VOID,

    /** A page with its total result count. */
    PAGE,

    /** A slice without a total result count. */
    SLICE,

    /** The number of rows affected by an `@Modifying` query. */
    MODIFYING,
}
