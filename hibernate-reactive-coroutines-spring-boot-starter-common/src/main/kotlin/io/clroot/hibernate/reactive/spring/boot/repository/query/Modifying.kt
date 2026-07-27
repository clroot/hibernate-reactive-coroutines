package io.clroot.hibernate.reactive.spring.boot.repository.query

/**
 * UPDATE/DELETE 쿼리임을 표시합니다.
 * @Query와 함께 사용하며, 반환 타입은 Int (영향받은 행 수) 또는 Unit입니다.
 *
 * @param clearAutomatically 쿼리 실행 후 현재 세션의 영속성 컨텍스트를 비울지 여부
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Modifying(
    val clearAutomatically: Boolean = false,
)
