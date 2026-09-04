package io.clroot.hibernate.reactive.spring.boot.auditing

import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import java.time.Instant

/**
 * JPA entity listener that sets auditing timestamps during lifecycle callbacks.
 *
 * Populates fields annotated with `@CreatedDate` and `@LastModifiedDate`.
 *
 * Usage:
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
 * Supported field types:
 * - `java.time.Instant`
 * - `java.time.LocalDateTime`
 * - `java.time.OffsetDateTime`
 * - `java.time.ZonedDateTime`
 * - `java.util.Date`
 * - `Long` (milliseconds since the epoch)
 *
 * `@CreatedBy` and `@LastModifiedBy` require asynchronous auditor lookup and are populated by
 * the repository's `save()` method instead.
 *
 * @see ReactiveAuditingHandler
 */
public class AuditingEntityListener {

    @PrePersist
    public fun onPrePersist(entity: Any) {
        // A shared instant preserves `createdAt == updatedAt` for unmodified entities.
        val now = Instant.now()
        AuditMetadata.setCreatedDate(entity, now)
        AuditMetadata.setLastModifiedDate(entity, now)
    }

    @PreUpdate
    public fun onPreUpdate(entity: Any) {
        AuditMetadata.setLastModifiedDate(entity)
    }
}
