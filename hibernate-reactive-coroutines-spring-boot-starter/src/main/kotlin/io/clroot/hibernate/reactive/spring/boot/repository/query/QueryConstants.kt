package io.clroot.hibernate.reactive.spring.boot.repository.query

/**
 * 쿼리 처리에 사용되는 공통 상수들.
 */
internal object QueryConstants {
    /** ORDER BY 절을 찾기 위한 정규식 */
    val ORDER_BY_REGEX = Regex(" ORDER BY .+$", RegexOption.IGNORE_CASE)
}
