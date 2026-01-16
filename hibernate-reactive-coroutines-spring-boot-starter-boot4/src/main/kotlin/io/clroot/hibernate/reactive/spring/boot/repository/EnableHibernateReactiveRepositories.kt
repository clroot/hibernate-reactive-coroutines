package io.clroot.hibernate.reactive.spring.boot.repository

import org.springframework.context.annotation.Import
import org.springframework.core.annotation.AliasFor
import kotlin.reflect.KClass

/**
 * Hibernate Reactive 기반 CoroutineCrudRepository 스캔을 활성화하는 어노테이션.
 *
 * 기본적으로 Auto-configuration이 활성화되어 있으므로 이 어노테이션 없이도 동작합니다.
 * 특정 패키지만 스캔하고 싶을 때 이 어노테이션을 사용합니다.
 *
 * 사용 예:
 * ```kotlin
 * // 기본: @SpringBootApplication 패키지 기준으로 스캔
 * @SpringBootApplication
 * class MyApplication
 *
 * // 특정 패키지만 스캔
 * @EnableHibernateReactiveRepositories(basePackages = ["com.example.domain.repository"])
 * @SpringBootApplication
 * class MyApplication
 *
 * // 마커 클래스 기준으로 스캔
 * @EnableHibernateReactiveRepositories(basePackageClasses = [UserRepository::class])
 * @SpringBootApplication
 * class MyApplication
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Import(HibernateReactiveRepositoriesRegistrarSelector::class)
annotation class EnableHibernateReactiveRepositories(
    /**
     * 스캔할 베이스 패키지 (basePackages의 별칭)
     */
    @get:AliasFor("basePackages")
    val value: Array<String> = [],

    /**
     * 스캔할 베이스 패키지
     */
    @get:AliasFor("value")
    val basePackages: Array<String> = [],

    /**
     * 스캔할 베이스 패키지를 결정하는 마커 클래스
     * 해당 클래스가 위치한 패키지가 베이스 패키지로 사용됩니다.
     */
    val basePackageClasses: Array<KClass<*>> = [],
)
