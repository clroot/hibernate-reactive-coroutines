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
 * Caches auditing metadata for entity classes.
 *
 * Field metadata is cached per class to avoid repeated reflection.
 */
internal object AuditMetadata {

    private val logger = LoggerFactory.getLogger(AuditMetadata::class.java)

    private val cache = ConcurrentHashMap<Class<*>, EntityAuditInfo>()

    fun getAuditInfo(entityClass: Class<*>): EntityAuditInfo {
        return cache.computeIfAbsent(entityClass) { cls ->
            extractAuditInfo(cls)
        }
    }

    /**
     * Sets the `@CreatedDate` field when it has not already been set.
     *
     * @param now The reference time. Defaults to the current time.
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
     * Sets the `@LastModifiedDate` field.
     *
     * @param now The reference time. During creation, pass the same value used for the
     *   creation timestamp so `createdAt == updatedAt` identifies an unmodified entity.
     */
    fun setLastModifiedDate(entity: Any, now: Instant = Instant.now()) {
        val auditInfo = getAuditInfo(entity.javaClass)
        auditInfo.lastModifiedDateField?.let { field ->
            setTemporalValue(entity, field, now)
        }
    }

    fun setCreatedBy(entity: Any, auditor: Any) {
        val auditInfo = getAuditInfo(entity.javaClass)
        auditInfo.createdByField?.let { field ->
            if (getFieldValueSafely(entity, field) == null) {
                setFieldValueSafely(entity, field, auditor)
            }
        }
    }

    fun setLastModifiedBy(entity: Any, auditor: Any) {
        val auditInfo = getAuditInfo(entity.javaClass)
        auditInfo.lastModifiedByField?.let { field ->
            setFieldValueSafely(entity, field, auditor)
        }
    }

    /**
     * Reads a field value, logging reflection access failures and treating them as absent.
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
     * Writes a field value and logs reflection access failures.
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
     * Converts the reference time to the field's supported temporal type.
     *
     * The caller supplies the reference time so creation and modification timestamps can match.
     */
    private fun setTemporalValue(entity: Any, field: Field, now: Instant) {
        val value: Any = when (field.type) {
            Instant::class.java -> now
            LocalDateTime::class.java -> LocalDateTime.ofInstant(now, ZoneId.systemDefault())
            OffsetDateTime::class.java -> OffsetDateTime.ofInstant(now, ZoneId.systemDefault())
            ZonedDateTime::class.java -> ZonedDateTime.ofInstant(now, ZoneId.systemDefault())
            Date::class.java -> Date.from(now)
            Long::class.javaObjectType, Long::class.javaPrimitiveType -> now.toEpochMilli()
            else -> return
        }
        setFieldValueSafely(entity, field, value)
    }

    private fun extractAuditInfo(cls: Class<*>): EntityAuditInfo {
        var idField: Field? = null
        var versionField: Field? = null
        var createdDateField: Field? = null
        var lastModifiedDateField: Field? = null
        var createdByField: Field? = null
        var lastModifiedByField: Field? = null

        // Include inherited fields because auditing annotations may be declared on a mapped superclass.
        var currentClass: Class<*>? = cls
        while (currentClass != null && currentClass != Any::class.java) {
            for (field in currentClass.declaredFields) {
                if (!tryMakeAccessible(field)) {
                    continue
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
     * Makes a field accessible for reflection.
     *
     * Packages that are not opened on the module path can reject reflective access.
     */
    private fun tryMakeAccessible(field: Field): Boolean {
        return try {
            field.trySetAccessible()
        } catch (e: SecurityException) {
            logger.debug("Cannot make field '${field.name}' accessible due to security restrictions", e)
            false
        } catch (e: RuntimeException) {
            logger.debug("Cannot make field '${field.name}' accessible", e)
            false
        }
    }

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
     * Warns when an auditing annotation targets an unsupported temporal type.
     *
     * Without this warning, the issue may only surface later as a NOT NULL constraint violation.
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
