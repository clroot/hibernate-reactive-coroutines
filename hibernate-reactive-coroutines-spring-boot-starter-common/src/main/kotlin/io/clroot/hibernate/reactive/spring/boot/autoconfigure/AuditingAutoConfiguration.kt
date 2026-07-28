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
 *     override suspend fun getCurrentAuditor(): String? =
 *         ReactiveSecurityContextHolder.getContext()
 *             .awaitSingleOrNull()
 *             ?.authentication
 *             ?.name
 * }
 * ```
 *
 * WebFlux에서는 `SecurityContextHolder`(ThreadLocal)가 채워지지 않으므로 반드시
 * `ReactiveSecurityContextHolder`를 사용해야 합니다. ThreadLocal을 읽으면 감사자가
 * 항상 null이 되어 `@CreatedBy`가 조용히 비어 있게 됩니다.
 *
 * Note: `@CreatedDate`, `@LastModifiedDate`는 [io.clroot.hibernate.reactive.spring.boot.auditing.AuditingEntityListener]를
 * 통해 처리됩니다. 엔티티에 `@EntityListeners(AuditingEntityListener::class)`를 추가해야 합니다.
 */
@AutoConfiguration
public class AuditingAutoConfiguration {

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
