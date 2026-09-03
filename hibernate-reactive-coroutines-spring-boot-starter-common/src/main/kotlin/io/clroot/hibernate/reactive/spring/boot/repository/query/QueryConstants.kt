package io.clroot.hibernate.reactive.spring.boot.repository.query

/** Query-processing constants used by the Spring adapter. */
internal object QueryConstants {
    val ORDER_BY_REGEX = Regex(" ORDER BY .+$", RegexOption.IGNORE_CASE)
}
