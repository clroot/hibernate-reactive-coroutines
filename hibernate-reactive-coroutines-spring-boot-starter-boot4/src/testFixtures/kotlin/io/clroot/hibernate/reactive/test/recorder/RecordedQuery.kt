package io.clroot.hibernate.reactive.test.recorder

/**
 * 기록된 HQL 쿼리 정보.
 *
 * @property hql 실행된 HQL 쿼리 문자열
 * @property queryType 쿼리 타입 (SELECT, UPDATE, DELETE, COUNT, NATIVE)
 * @property timestamp 쿼리 기록 시간 (밀리초)
 */
data class RecordedQuery(
    val hql: String,
    val queryType: QueryType,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * 쿼리 타입.
 */
enum class QueryType {
    SELECT,
    UPDATE,
    DELETE,
    COUNT,
    NATIVE,
}
