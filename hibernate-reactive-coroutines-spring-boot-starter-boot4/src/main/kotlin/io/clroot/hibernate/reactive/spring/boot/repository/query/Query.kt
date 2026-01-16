package io.clroot.hibernate.reactive.spring.boot.repository.query

/**
 * JPQL 또는 네이티브 SQL 쿼리를 직접 지정합니다.
 *
 * @param value JPQL 또는 네이티브 SQL 쿼리
 * @param nativeQuery true면 네이티브 SQL로 실행
 * @param countQuery Page 반환 시 사용할 COUNT 쿼리 (생략 시 자동 생성)
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Query(
    val value: String,
    val nativeQuery: Boolean = false,
    val countQuery: String = "",
)
