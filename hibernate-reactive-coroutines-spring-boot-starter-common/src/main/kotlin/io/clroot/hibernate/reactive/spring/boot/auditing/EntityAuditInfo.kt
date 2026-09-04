package io.clroot.hibernate.reactive.spring.boot.auditing

import java.lang.reflect.Field

/**
 * Holds reflected auditing fields for an entity.
 */
internal data class EntityAuditInfo(
    val idField: Field?,
    val versionField: Field?,
    val createdDateField: Field?,
    val lastModifiedDateField: Field?,
    val createdByField: Field?,
    val lastModifiedByField: Field?,
) {
    fun hasAuditingFields(): Boolean {
        return createdDateField != null ||
                lastModifiedDateField != null ||
                createdByField != null ||
                lastModifiedByField != null
    }
}
