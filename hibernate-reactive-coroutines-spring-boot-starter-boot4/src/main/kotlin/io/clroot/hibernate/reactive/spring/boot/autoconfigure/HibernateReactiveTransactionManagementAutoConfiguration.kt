package io.clroot.hibernate.reactive.spring.boot.autoconfigure

import org.hibernate.reactive.mutiny.Mutiny
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.transaction.annotation.AbstractTransactionManagementConfiguration
import org.springframework.transaction.annotation.EnableTransactionManagement

/**
 * Enables Spring's annotation-driven transaction management for Spring Boot 4.
 *
 * Spring Boot 3 provides the equivalent infrastructure through its own
 * transaction auto-configuration.
 *
 * Backs off when the application already declares `@EnableTransactionManagement`, so that a
 * user-chosen `proxyTargetClass` or `mode` is not silently competing with this declaration.
 */
@AutoConfiguration(after = [HibernateReactiveAutoConfiguration::class])
@ConditionalOnClass(Mutiny.SessionFactory::class)
@ConditionalOnMissingBean(AbstractTransactionManagementConfiguration::class)
@EnableTransactionManagement
internal class HibernateReactiveTransactionManagementAutoConfiguration
