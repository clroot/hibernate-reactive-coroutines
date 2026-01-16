package io.clroot.hibernate.reactive.spring.boot.auditing

/**
 * 엔티티의 감사자(사용자) 필드를 관리하는 핸들러.
 *
 * `@CreatedBy`와 `@LastModifiedBy` 어노테이션이 붙은 필드에
 * [ReactiveAuditorAware]에서 제공하는 현재 사용자 정보를 설정합니다.
 *
 * 이 핸들러는 Repository의 save() 메서드에서 호출되며,
 * suspend 함수를 통해 비동기적으로 현재 사용자 정보를 조회합니다.
 *
 * @param T 감사자 타입
 * @param auditorAware 현재 감사자를 제공하는 인터페이스 (선택적)
 */
class ReactiveAuditingHandler<T : Any>(
    private val auditorAware: ReactiveAuditorAware<T>?,
) {
    /**
     * 엔티티가 처음 생성될 때 감사자 필드를 설정합니다.
     *
     * `@CreatedBy`와 `@LastModifiedBy` 필드 모두에 현재 감사자를 설정합니다.
     *
     * @param entity 감사자 정보를 설정할 엔티티
     */
    suspend fun markCreated(entity: Any) {
        auditorAware?.getCurrentAuditor()?.let { auditor ->
            AuditMetadata.setCreatedBy(entity, auditor)
            AuditMetadata.setLastModifiedBy(entity, auditor)
        }
    }

    /**
     * 엔티티가 수정될 때 감사자 필드를 설정합니다.
     *
     * `@LastModifiedBy` 필드에만 현재 감사자를 설정합니다.
     *
     * @param entity 감사자 정보를 설정할 엔티티
     */
    suspend fun markModified(entity: Any) {
        auditorAware?.getCurrentAuditor()?.let { auditor ->
            AuditMetadata.setLastModifiedBy(entity, auditor)
        }
    }

    /**
     * AuditorAware가 설정되어 있는지 확인합니다.
     */
    fun hasAuditorAware(): Boolean = auditorAware != null
}
