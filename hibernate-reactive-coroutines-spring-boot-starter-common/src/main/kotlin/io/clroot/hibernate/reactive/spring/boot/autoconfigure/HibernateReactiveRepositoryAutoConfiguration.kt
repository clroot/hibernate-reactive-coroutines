package io.clroot.hibernate.reactive.spring.boot.autoconfigure

import io.clroot.hibernate.reactive.spring.boot.repository.HibernateReactiveRepositoryRegistrar
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * Auto-configuration for `CoroutineCrudRepository`.
 *
 * Scans and registers interfaces that extend `CoroutineCrudRepository`.
 */
@AutoConfiguration(after = [HibernateReactiveAutoConfiguration::class])
@ConditionalOnClass(CoroutineCrudRepository::class)
public class HibernateReactiveRepositoryAutoConfiguration {

    public companion object {
        /**
         * A static factory method lets Spring process this registry post-processor early.
         */
        @Bean
        @ConditionalOnMissingBean
        @JvmStatic
        public fun hibernateReactiveRepositoryRegistrar(): HibernateReactiveRepositoryRegistrar {
            return HibernateReactiveRepositoryRegistrar()
        }
    }
}
