package io.clroot.hibernate.reactive.repository.query

/** Validates property paths before an adapter interpolates them into a query. */
internal object QueryPropertyPathValidator {
    private val safePropertyPath =
        Regex("[\\p{L}_$][\\p{L}\\p{N}_$]*(\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)*")

    /** Return true when [property] is a safe path and does not use Java's `class` pseudo-property. */
    public fun isSafe(property: String): Boolean =
        safePropertyPath.matches(property) && "class" !in property.split('.')
}
