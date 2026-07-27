package io.clroot.hibernate.reactive.spring.boot.autoconfigure

import io.clroot.hibernate.reactive.spring.boot.repository.HibernateReactiveRepositoryRegistrar
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * CoroutineCrudRepository 자동 구성.
 *
 * CoroutineCrudRepository를 구현한 인터페이스를 자동으로 스캔하고 Bean으로 등록합니다.
 */
@AutoConfiguration(after = [HibernateReactiveAutoConfiguration::class])
@ConditionalOnClass(CoroutineCrudRepository::class)
class HibernateReactiveRepositoryAutoConfiguration {

    companion object {
        /**
         * static 메서드로 BeanDefinitionRegistryPostProcessor를 등록해야
         * Spring이 이른 시점에 처리할 수 있습니다.
         */
        @Bean
        @ConditionalOnMissingBean
        @JvmStatic
        fun hibernateReactiveRepositoryRegistrar(): HibernateReactiveRepositoryRegistrar {
            return HibernateReactiveRepositoryRegistrar()
        }
    }
}
