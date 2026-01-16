package io.clroot.hibernate.reactive.spring.boot.autoconfigure

import io.clroot.hibernate.reactive.spring.boot.auditing.ReactiveAuditingHandler
import io.clroot.hibernate.reactive.spring.boot.auditing.ReactiveAuditorAware
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Auditing 기능을 위한 Auto-configuration.
 *
 * [ReactiveAuditorAware] 빈이 등록되어 있으면 자동으로 [ReactiveAuditingHandler]를 생성합니다.
 * 이를 통해 `@CreatedBy`, `@LastModifiedBy` 필드가 자동으로 설정됩니다.
 *
 * 사용 방법:
 * ```kotlin
 * @Component
 * class SecurityAuditorAware : ReactiveAuditorAware<String> {
 *     override suspend fun getCurrentAuditor(): String? {
 *         return SecurityContextHolder.getContext()
 *             ?.authentication
 *             ?.name
 *     }
 * }
 * ```
 *
 * Note: `@CreatedDate`, `@LastModifiedDate`는 [io.clroot.hibernate.reactive.spring.boot.auditing.AuditingEntityListener]를
 * 통해 처리됩니다. 엔티티에 `@EntityListeners(AuditingEntityListener::class)`를 추가해야 합니다.
 */
@AutoConfiguration
class AuditingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun reactiveAuditingHandler(
        auditorAware: ObjectProvider<ReactiveAuditorAware<*>>,
    ): ReactiveAuditingHandler<*> {
        return ReactiveAuditingHandler(auditorAware.getIfAvailable())
    }
}
