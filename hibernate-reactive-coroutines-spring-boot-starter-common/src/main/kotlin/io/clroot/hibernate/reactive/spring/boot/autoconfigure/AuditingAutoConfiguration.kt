package io.clroot.hibernate.reactive.spring.boot.autoconfigure

import io.clroot.hibernate.reactive.repository.auditing.ReactiveAuditorAware
import io.clroot.hibernate.reactive.spring.boot.auditing.ReactiveAuditingHandler
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration for auditing.
 *
 * Creates a [ReactiveAuditingHandler] when a [ReactiveAuditorAware] bean is available,
 * enabling `@CreatedBy` and `@LastModifiedBy` population.
 *
 * Usage:
 * ```kotlin
 * @Component
 * class SecurityAuditorAware : ReactiveAuditorAware<String> {
 *     override suspend fun getCurrentAuditor(): String? =
 *         ReactiveSecurityContextHolder.getContext()
 *             .awaitSingleOrNull()
 *             ?.authentication
 *             ?.name
 * }
 * ```
 *
 * In WebFlux, use `ReactiveSecurityContextHolder` rather than the `SecurityContextHolder`
 * ThreadLocal. The ThreadLocal is not populated, so it would silently leave `@CreatedBy` empty.
 *
 * [io.clroot.hibernate.reactive.spring.boot.auditing.AuditingEntityListener] handles
 * `@CreatedDate` and `@LastModifiedDate`; add
 * `@EntityListeners(AuditingEntityListener::class)` to the entity.
 */
@AutoConfiguration
internal class AuditingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public fun reactiveAuditingHandler(
        auditorAware: ObjectProvider<ReactiveAuditorAware<*>>,
    ): ReactiveAuditingHandler<*> {
        val candidates = auditorAware.stream().toList()
        check(candidates.size <= 1) {
            "Expected at most one ReactiveAuditorAware bean but found ${candidates.size}. " +
                    "Declare a single one, or define your own ReactiveAuditingHandler bean to choose between them."
        }

        return ReactiveAuditingHandler(candidates.firstOrNull())
    }
}
