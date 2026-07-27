package io.clroot.hibernate.reactive.spring.boot.repository.query

/**
 * Named Parameter의 이름을 지정합니다.
 * 생략하면 Kotlin 파라미터 이름을 사용합니다.
 *
 * @param value 파라미터 이름 (:name 형식에서 name 부분)
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Param(val value: String)
