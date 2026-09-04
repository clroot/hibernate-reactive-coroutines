package io.clroot.hibernate.reactive.spring.boot.auditing

import io.clroot.hibernate.reactive.repository.auditing.ReactiveAuditorAware

/**
 * Populates entity auditor fields.
 *
 * Uses [ReactiveAuditorAware] to populate fields annotated with `@CreatedBy` and
 * `@LastModifiedBy`.
 *
 * Called by repository `save()` methods, it performs auditor lookup asynchronously.
 *
 * @param T The auditor type.
 * @param auditorAware Optional source of the current auditor.
 */
public class ReactiveAuditingHandler<T : Any>(
    private val auditorAware: ReactiveAuditorAware<T>?,
) {
    /** Populates creation and modification auditor fields for a new entity. */
    public suspend fun markCreated(entity: Any) {
        auditorAware?.getCurrentAuditor()?.let { auditor ->
            AuditMetadata.setCreatedBy(entity, auditor)
            AuditMetadata.setLastModifiedBy(entity, auditor)
        }
    }

    /** Populates the modification auditor field for an existing entity. */
    public suspend fun markModified(entity: Any) {
        auditorAware?.getCurrentAuditor()?.let { auditor ->
            AuditMetadata.setLastModifiedBy(entity, auditor)
        }
    }

    public fun hasAuditorAware(): Boolean = auditorAware != null
}
