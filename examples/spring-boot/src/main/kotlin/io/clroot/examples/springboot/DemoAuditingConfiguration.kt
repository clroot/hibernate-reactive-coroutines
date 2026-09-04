package io.clroot.examples.springboot

import io.clroot.hibernate.reactive.repository.auditing.ReactiveAuditorAware
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class DemoAuditingConfiguration {
    @Bean
    fun demoAuditorAware(): ReactiveAuditorAware<String> = ReactiveAuditorAware { "demo-user" }
}
