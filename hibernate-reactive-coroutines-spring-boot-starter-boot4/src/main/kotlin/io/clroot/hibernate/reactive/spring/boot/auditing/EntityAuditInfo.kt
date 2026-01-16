package io.clroot.hibernate.reactive.spring.boot.auditing

import java.lang.reflect.Field

/**
 * 엔티티의 Auditing 관련 필드 정보를 보관합니다.
 */
internal data class EntityAuditInfo(
    val idField: Field?,
    val versionField: Field?,
    val createdDateField: Field?,
    val lastModifiedDateField: Field?,
    val createdByField: Field?,
    val lastModifiedByField: Field?,
) {
    /**
     * Auditing 필드가 하나라도 있는지 확인합니다.
     */
    fun hasAuditingFields(): Boolean {
        return createdDateField != null ||
                lastModifiedDateField != null ||
                createdByField != null ||
                lastModifiedByField != null
    }
}
