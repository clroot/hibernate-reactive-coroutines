package io.clroot.hibernate.reactive.repository.query

/**
 * Declares an HQL/JPQL or native SQL repository query.
 *
 * This annotation is shared by all HRC repository integrations. Framework-specific pagination and
 * sorting types remain supported at each integration boundary.
 *
 * @property value HQL/JPQL or native SQL query text
 * @property nativeQuery whether [value] is native SQL instead of HQL/JPQL
 * @property countQuery explicit count query for a paged result; derived automatically when omitted
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Query(
    public val value: String,
    public val nativeQuery: Boolean = false,
    public val countQuery: String = "",
)
