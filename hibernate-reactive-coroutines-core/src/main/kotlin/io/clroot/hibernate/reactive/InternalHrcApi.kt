package io.clroot.hibernate.reactive

/**
 * Marks public declarations used only as contracts between HRC artifacts.
 *
 * These declarations remain ABI-checked because the artifacts are published separately, but they
 * are not part of the supported application interface and may change without migration support.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This declaration is an internal contract between Hibernate Reactive Coroutines artifacts.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
public annotation class InternalHrcApi
