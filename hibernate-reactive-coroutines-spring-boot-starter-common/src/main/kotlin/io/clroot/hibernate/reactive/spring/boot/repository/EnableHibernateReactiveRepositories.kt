package io.clroot.hibernate.reactive.spring.boot.repository

import org.springframework.context.annotation.Import
import org.springframework.core.annotation.AliasFor
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import kotlin.reflect.KClass

/**
 * Enables scanning for Hibernate Reactive [CoroutineCrudRepository] interfaces.
 *
 * Auto-configuration scans the application package by default. Use this annotation to limit scanning to
 * specific packages.
 *
 * Example:
 * ```kotlin
 * // Scan the @SpringBootApplication package.
 * @SpringBootApplication
 * class MyApplication
 *
 * // Scan only a specific package.
 * @EnableHibernateReactiveRepositories(basePackages = ["com.example.domain.repository"])
 * @SpringBootApplication
 * class MyApplication
 *
 * // Derive a package from a marker class.
 * @EnableHibernateReactiveRepositories(basePackageClasses = [UserRepository::class])
 * @SpringBootApplication
 * class MyApplication
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Import(HibernateReactiveRepositoriesRegistrarSelector::class)
public annotation class EnableHibernateReactiveRepositories(
    /**
     * Alias for [basePackages].
     */
    @get:AliasFor("basePackages")
    val value: Array<String> = [],

    /** Base packages to scan. */
    @get:AliasFor("value")
    val basePackages: Array<String> = [],

    /**
     * Marker classes whose packages are scanned.
     */
    val basePackageClasses: Array<KClass<*>> = [],
)
