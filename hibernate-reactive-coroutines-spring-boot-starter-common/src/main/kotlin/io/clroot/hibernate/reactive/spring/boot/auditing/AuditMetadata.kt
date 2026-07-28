package io.clroot.hibernate.reactive.spring.boot.auditing

import jakarta.persistence.Id
import jakarta.persistence.Version
import org.slf4j.LoggerFactory
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import java.lang.reflect.Field
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
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
     * @CreatedDate 필드에 현재 시간을 설정합니다.
     *
     * @param now 기준 시각. 생략하면 현재 시각을 사용합니다.
     */
    fun setCreatedDate(entity: Any, now: Instant = Instant.now()) {
        val auditInfo = getAuditInfo(entity.javaClass)
        auditInfo.createdDateField?.let { field ->
            val currentValue = getFieldValueSafely(entity, field)
            val isUnsetPrimitiveLong =
                field.type == Long::class.javaPrimitiveType && currentValue == 0L
            if (currentValue == null || isUnsetPrimitiveLong) {
                setTemporalValue(entity, field, now)
            }
        }
    }

    /**
     * @LastModifiedDate 필드에 현재 시간을 설정합니다.
     *
     * @param now 기준 시각. 생성 시점에는 createdDate와 같은 값을 넘겨야
     *   `createdAt == updatedAt`으로 "한 번도 수정되지 않음"을 판별할 수 있습니다.
     */
    fun setLastModifiedDate(entity: Any, now: Instant = Instant.now()) {
        val auditInfo = getAuditInfo(entity.javaClass)
        auditInfo.lastModifiedDateField?.let { field ->
            setTemporalValue(entity, field, now)
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
     * 필드 타입에 맞는 시간 값을 설정합니다.
     *
     * @param now 기준 시각. 같은 저장에서 생성/수정 시각을 동일하게 맞추기 위해 호출자가 전달합니다.
     */
    private fun setTemporalValue(entity: Any, field: Field, now: Instant) {
        val value: Any = when (field.type) {
            Instant::class.java -> now
            LocalDateTime::class.java -> LocalDateTime.ofInstant(now, ZoneId.systemDefault())
            OffsetDateTime::class.java -> OffsetDateTime.ofInstant(now, ZoneId.systemDefault())
            ZonedDateTime::class.java -> ZonedDateTime.ofInstant(now, ZoneId.systemDefault())
            Date::class.java -> Date.from(now)
            Long::class.javaObjectType, Long::class.javaPrimitiveType -> now.toEpochMilli()
            else -> return // isSupportedTemporalType이 걸러내므로 도달하지 않음
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
                        } else {
                            warnUnsupportedTemporalType(cls, field, "@CreatedDate")
                        }
                    }

                    field.isAnnotationPresent(LastModifiedDate::class.java) && lastModifiedDateField == null -> {
                        if (isSupportedTemporalType(field.type)) {
                            lastModifiedDateField = field
                        } else {
                            warnUnsupportedTemporalType(cls, field, "@LastModifiedDate")
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
            // 모듈 경로에서 열리지 않은 패키지는 InaccessibleObjectException을 던진다.
            field.trySetAccessible()
        } catch (e: SecurityException) {
            logger.debug("Cannot make field '${field.name}' accessible due to security restrictions", e)
            false
        } catch (e: RuntimeException) {
            logger.debug("Cannot make field '${field.name}' accessible", e)
            false
        }
    }

    /**
     * 지원되는 시간 타입인지 확인합니다.
     */
    private fun isSupportedTemporalType(type: Class<*>): Boolean {
        return type == Instant::class.java ||
                type == LocalDateTime::class.java ||
                type == OffsetDateTime::class.java ||
                type == ZonedDateTime::class.java ||
                type == Date::class.java ||
                type == Long::class.javaObjectType ||
                type == Long::class.javaPrimitiveType
    }

    /**
     * 지원하지 않는 타입에 auditing 어노테이션이 붙으면 조용히 넘어가지 않고 경고합니다.
     *
     * 경고가 없으면 필드가 계속 null로 남아 NOT NULL 제약 위반으로만 문제가 드러납니다.
     */
    private fun warnUnsupportedTemporalType(cls: Class<*>, field: Field, annotation: String) {
        logger.warn(
            "{}.{} is annotated with {} but its type {} is not supported; " +
                    "the field will not be populated. Supported types: " +
                    "Instant, LocalDateTime, OffsetDateTime, ZonedDateTime, java.util.Date, Long.",
            cls.name,
            field.name,
            annotation,
            field.type.name,
        )
    }
}
