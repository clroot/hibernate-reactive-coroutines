package io.clroot.hibernate.reactive.repository.auditing

import io.clroot.hibernate.reactive.InternalHrcApi
import io.clroot.hibernate.reactive.repository.runtime.RepositoryEntityLifecycle
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/** Marks the nullable field populated with the auditor that created the entity. */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
public annotation class CreatedBy

/** Marks the nullable field populated with the auditor that most recently modified the entity. */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
public annotation class LastModifiedBy

/** Applies annotation-based auditor fields before repository save operations. */
@InternalHrcApi
public class AuditingEntityLifecycle(
    private val auditorAware: ReactiveAuditorAware<*>?,
) : RepositoryEntityLifecycle {
    override suspend fun beforeSave(entity: Any, isNew: Boolean) {
        val auditor = auditorAware?.getCurrentAuditor() ?: return
        val fields = metadata.computeIfAbsent(entity.javaClass, ::inspect)

        if (isNew) {
            fields.createdBy?.let { field ->
                if (field.get(entity) == null) setAuditor(entity, field, auditor)
            }
        }
        fields.lastModifiedBy?.let { field -> setAuditor(entity, field, auditor) }
    }

    private fun setAuditor(entity: Any, field: Field, auditor: Any) {
        require(boxed(field.type).isInstance(auditor)) {
            "Auditor type ${auditor.javaClass.name} is not assignable to " +
                "${entity.javaClass.name}.${field.name} (${field.type.name})"
        }
        field.set(entity, auditor)
    }

    private fun inspect(entityClass: Class<*>): AuditorFields {
        var createdBy: Field? = null
        var lastModifiedBy: Field? = null
        var current: Class<*>? = entityClass

        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { field ->
                when {
                    field.isAnnotationPresent(CreatedBy::class.java) && createdBy == null -> {
                        makeAccessible(entityClass, field)
                        createdBy = field
                    }
                    field.isAnnotationPresent(LastModifiedBy::class.java) && lastModifiedBy == null -> {
                        makeAccessible(entityClass, field)
                        lastModifiedBy = field
                    }
                }
            }
            current = current.superclass
        }
        return AuditorFields(createdBy, lastModifiedBy)
    }

    private fun makeAccessible(entityClass: Class<*>, field: Field) {
        check(field.trySetAccessible()) {
            "Cannot access auditing field ${entityClass.name}.${field.name}"
        }
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
        Char::class.javaPrimitiveType -> Char::class.javaObjectType
        Short::class.javaPrimitiveType -> Short::class.javaObjectType
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        Double::class.javaPrimitiveType -> Double::class.javaObjectType
        else -> type
    }

    private data class AuditorFields(
        val createdBy: Field?,
        val lastModifiedBy: Field?,
    )

    private companion object {
        val metadata: ConcurrentHashMap<Class<*>, AuditorFields> = ConcurrentHashMap()
    }
}
