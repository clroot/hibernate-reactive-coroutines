package io.clroot.hibernate.reactive.repository.query

/**
 * Hibernate Reactive options for Jakarta Data [jakarta.data.repository.Query].
 *
 * Jakarta Data 1.0 does not expose native-query, explicit count-query, or persistence-context
 * clearing metadata. Use this annotation only alongside Jakarta Data `@Query` when one of those
 * provider-specific capabilities is required.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
public annotation class QueryOptions(
    public val nativeQuery: Boolean = false,
    public val countQuery: String = "",
    public val clearAutomatically: Boolean = false,
)
