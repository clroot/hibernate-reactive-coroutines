package io.clroot.hibernate.reactive.repository.auditing

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
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
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
