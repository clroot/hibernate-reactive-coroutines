package io.clroot.hibernate.reactive.repository.query

/**
 * Marks a [Query] as an update or delete operation.
 *
 * @property clearAutomatically whether to clear the persistence context after executing the query
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Modifying(
    public val clearAutomatically: Boolean = false,
)
