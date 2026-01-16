package io.clroot.hibernate.reactive.spring.boot.repository.query

/**
 * UPDATE/DELETE 쿼리임을 표시합니다.
 * @Query와 함께 사용하며, 반환 타입은 Int (영향받은 행 수)입니다.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Modifying
