package io.clroot.hibernate.reactive.repository.query

/**
 * Binds a repository method parameter to a named parameter in [Query].
 *
 * When omitted, HRC uses the Kotlin parameter name.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Param(public val value: String)
