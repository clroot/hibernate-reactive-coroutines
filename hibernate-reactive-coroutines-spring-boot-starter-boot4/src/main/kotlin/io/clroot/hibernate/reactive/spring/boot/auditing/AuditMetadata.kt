package io.clroot.hibernate.reactive.spring.boot.auditing

import jakarta.persistence.Id
import jakarta.persistence.Version
import org.slf4j.LoggerFactory
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.domain.Persistable
import java.lang.reflect.Field
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * 엔티티 클래스의 Auditing 메타데이터를 캐싱하고 관리합니다.
 *
 * 리플렉션 비용을 줄이기 위해 클래스별로 필드 정보를 캐싱합니다.
 */
internal object AuditMetadata {

    private val logger = LoggerFactory.getLogger(AuditMetadata::class.java)

    private val cache = ConcurrentHashMap<Class<*>, EntityAuditInfo>()

    /**
     * 엔티티의 Auditing 정보를 가져옵니다.
     */
    fun getAuditInfo(entityClass: Class<*>): EntityAuditInfo {
        return cache.computeIfAbsent(entityClass) { cls ->
            extractAuditInfo(cls)
        }
    }

    /**
     * 엔티티가 신규인지 판단합니다.
     *
     * 판단 순서:
     * 1. Persistable 인터페이스 구현 시 isNew() 호출
     * 2. @Version 필드가 있고 null이거나 0이면 신규
     * 3. @Id 필드가 null이면 신규
     */
    fun isNew(entity: Any): Boolean {
        // 1. Persistable 인터페이스 확인
        if (entity is Persistable<*>) {
            return entity.isNew
        }

        val auditInfo = getAuditInfo(entity.javaClass)

        // 2. @Version 필드 확인
        auditInfo.versionField?.let { field ->
            val value = getFieldValueSafely(entity, field)
            return value == null || (value is Number && value.toLong() == 0L)
        }

        // 3. @Id 필드 확인
        auditInfo.idField?.let { field ->
            return getFieldValueSafely(entity, field) == null
        }

        // 판단 불가 시 신규로 간주
        return true
    }

    /**
     * @CreatedDate 필드에 현재 시간을 설정합니다.
     */
    fun setCreatedDate(entity: Any) {
        val auditInfo = getAuditInfo(entity.javaClass)
        auditInfo.createdDateField?.let { field ->
            if (getFieldValueSafely(entity, field) == null) {
                setTemporalValue(entity, field)
            }
        }
    }

    /**
     * @LastModifiedDate 필드에 현재 시간을 설정합니다.
     */
    fun setLastModifiedDate(entity: Any) {
        val auditInfo = getAuditInfo(entity.javaClass)
        auditInfo.lastModifiedDateField?.let { field ->
            setTemporalValue(entity, field)
        }
    }

    /**
     * @CreatedBy 필드에 감사자를 설정합니다.
     */
    fun setCreatedBy(entity: Any, auditor: Any) {
        val auditInfo = getAuditInfo(entity.javaClass)
        auditInfo.createdByField?.let { field ->
            if (getFieldValueSafely(entity, field) == null) {
                setFieldValueSafely(entity, field, auditor)
            }
        }
    }

    /**
     * @LastModifiedBy 필드에 감사자를 설정합니다.
     */
    fun setLastModifiedBy(entity: Any, auditor: Any) {
        val auditInfo = getAuditInfo(entity.javaClass)
        auditInfo.lastModifiedByField?.let { field ->
            setFieldValueSafely(entity, field, auditor)
        }
    }

    /**
     * 필드 값을 안전하게 읽습니다.
     * SecurityException 발생 시 null을 반환하고 경고를 로깅합니다.
     */
    private fun getFieldValueSafely(entity: Any, field: Field): Any? {
        return try {
            field.get(entity)
        } catch (e: SecurityException) {
            logger.warn(
                "Cannot access field '${field.name}' on ${entity.javaClass.name} due to security restrictions",
                e
            )
            null
        } catch (e: IllegalAccessException) {
            logger.warn("Cannot access field '${field.name}' on ${entity.javaClass.name}", e)
            null
        }
    }

    /**
     * 필드 값을 안전하게 설정합니다.
     * 예외 발생 시 경고를 로깅하고 무시합니다.
     */
    private fun setFieldValueSafely(entity: Any, field: Field, value: Any) {
        try {
            field.set(entity, value)
        } catch (e: SecurityException) {
            logger.warn("Cannot set field '${field.name}' on ${entity.javaClass.name} due to security restrictions", e)
        } catch (e: IllegalAccessException) {
            logger.warn("Cannot set field '${field.name}' on ${entity.javaClass.name}", e)
        }
    }

    /**
     * 필드 타입에 맞는 현재 시간 값을 설정합니다.
     */
    private fun setTemporalValue(entity: Any, field: Field) {
        val value: Any = when (field.type) {
            Instant::class.java -> Instant.now()
            LocalDateTime::class.java -> LocalDateTime.now()
            ZonedDateTime::class.java -> ZonedDateTime.now()
            Date::class.java -> Date()
            Long::class.java, Long::class.javaPrimitiveType -> System.currentTimeMillis()
            else -> return // 지원하지 않는 타입
        }
        setFieldValueSafely(entity, field, value)
    }

    /**
     * 클래스에서 Auditing 관련 필드를 추출합니다.
     */
    private fun extractAuditInfo(cls: Class<*>): EntityAuditInfo {
        var idField: Field? = null
        var versionField: Field? = null
        var createdDateField: Field? = null
        var lastModifiedDateField: Field? = null
        var createdByField: Field? = null
        var lastModifiedByField: Field? = null

        // 상위 클래스 포함 모든 필드 탐색
        var currentClass: Class<*>? = cls
        while (currentClass != null && currentClass != Any::class.java) {
            for (field in currentClass.declaredFields) {
                if (!tryMakeAccessible(field)) {
                    continue // 접근 불가한 필드는 스킵
                }

                when {
                    field.isAnnotationPresent(Id::class.java) && idField == null -> {
                        idField = field
                    }

                    field.isAnnotationPresent(Version::class.java) && versionField == null -> {
                        versionField = field
                    }

                    field.isAnnotationPresent(CreatedDate::class.java) && createdDateField == null -> {
                        if (isSupportedTemporalType(field.type)) {
                            createdDateField = field
                        }
                    }

                    field.isAnnotationPresent(LastModifiedDate::class.java) && lastModifiedDateField == null -> {
                        if (isSupportedTemporalType(field.type)) {
                            lastModifiedDateField = field
                        }
                    }

                    field.isAnnotationPresent(CreatedBy::class.java) && createdByField == null -> {
                        createdByField = field
                    }

                    field.isAnnotationPresent(LastModifiedBy::class.java) && lastModifiedByField == null -> {
                        lastModifiedByField = field
                    }
                }
            }
            currentClass = currentClass.superclass
        }

        return EntityAuditInfo(
            idField = idField,
            versionField = versionField,
            createdDateField = createdDateField,
            lastModifiedDateField = lastModifiedDateField,
            createdByField = createdByField,
            lastModifiedByField = lastModifiedByField,
        )
    }

    /**
     * 필드를 접근 가능하게 설정합니다.
     *
     * @return 접근 가능하게 설정되었으면 true, 실패하면 false
     */
    private fun tryMakeAccessible(field: Field): Boolean {
        return try {
            field.isAccessible = true
            true
        } catch (e: SecurityException) {
            logger.debug("Cannot make field '${field.name}' accessible due to security restrictions", e)
            false
        }
    }

    /**
     * 지원되는 시간 타입인지 확인합니다.
     */
    private fun isSupportedTemporalType(type: Class<*>): Boolean {
        return type == Instant::class.java ||
                type == LocalDateTime::class.java ||
                type == ZonedDateTime::class.java ||
                type == Date::class.java ||
                type == Long::class.java ||
                type == Long::class.javaPrimitiveType
    }
}
