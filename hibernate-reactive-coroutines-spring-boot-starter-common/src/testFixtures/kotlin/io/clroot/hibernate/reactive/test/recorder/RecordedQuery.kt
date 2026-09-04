package io.clroot.hibernate.reactive.test.recorder

/**
 * Recorded HQL query.
 *
 * @property timestamp Recording time in milliseconds.
 */
data class RecordedQuery(
    val hql: String,
    val queryType: QueryType,
    val timestamp: Long = System.currentTimeMillis(),
)

/** Query type. */
enum class QueryType {
    SELECT,
    UPDATE,
    DELETE,
    COUNT,
    NATIVE,
}
