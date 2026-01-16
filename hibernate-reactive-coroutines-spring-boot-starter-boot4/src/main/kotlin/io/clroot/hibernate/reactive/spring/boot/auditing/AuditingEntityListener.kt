package io.clroot.hibernate.reactive.spring.boot.auditing

import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate

/**
 * JPA 엔티티 라이프사이클 콜백을 통해 Auditing 타임스탬프를 자동으로 설정하는 리스너.
 *
 * `@CreatedDate`와 `@LastModifiedDate` 어노테이션이 붙은 필드에 자동으로 현재 시간을 설정합니다.
 *
 * 사용 방법:
 * ```kotlin
 * @Entity
 * @EntityListeners(AuditingEntityListener::class)
 * class User(
 *     @Id @GeneratedValue
 *     val id: Long? = null,
 *
 *     var name: String,
 *
 *     @CreatedDate
 *     var createdAt: LocalDateTime? = null,
 *
 *     @LastModifiedDate
 *     var updatedAt: LocalDateTime? = null,
 * )
 * ```
 *
 * 지원되는 필드 타입:
 * - `java.time.Instant`
 * - `java.time.LocalDateTime`
 * - `java.time.ZonedDateTime`
 * - `java.util.Date`
 * - `Long` (밀리초 타임스탬프)
 *
 * Note: `@CreatedBy`와 `@LastModifiedBy`는 비동기 컨텍스트에서 사용자 정보를 조회해야 하므로
 * 이 리스너에서는 처리하지 않습니다. 대신 Repository의 save() 메서드에서 처리됩니다.
 *
 * @see ReactiveAuditingHandler
 */
class AuditingEntityListener {

    /**
     * 엔티티가 처음 저장되기 전에 호출됩니다.
     *
     * `@CreatedDate`와 `@LastModifiedDate` 필드에 현재 시간을 설정합니다.
     */
    @PrePersist
    fun onPrePersist(entity: Any) {
        AuditMetadata.setCreatedDate(entity)
        AuditMetadata.setLastModifiedDate(entity)
    }

    /**
     * 엔티티가 수정되기 전에 호출됩니다.
     *
     * `@LastModifiedDate` 필드에 현재 시간을 설정합니다.
     */
    @PreUpdate
    fun onPreUpdate(entity: Any) {
        AuditMetadata.setLastModifiedDate(entity)
    }
}
