package io.clroot.hibernate.reactive.spring.boot.repository

import jakarta.persistence.EmbeddedId
import jakarta.persistence.Id
import jakarta.persistence.Version
import org.springframework.data.domain.Persistable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Determines whether a repository entity should be persisted or merged.
 *
 * The rules follow Spring Data's default new-state semantics:
 * [Persistable] takes precedence, a nullable version is used when present,
 * and otherwise null or primitive-default identifiers represent a new entity.
 */
internal object EntityStateDetector {
    private val cache = ConcurrentHashMap<Class<*>, EntityStateMetadata>()

    fun isNew(entity: Any): Boolean {
        if (entity is Persistable<*>) {
            return entity.isNew
        }

        return cache.computeIfAbsent(entity.javaClass, ::inspect).isNew(entity)
    }

    private fun inspect(entityClass: Class<*>): EntityStateMetadata {
        val identifiers = mutableListOf<ValueAccessor>()
        var version: ValueAccessor? = null
        var currentClass: Class<*>? = entityClass

        while (currentClass != null && currentClass != Any::class.java) {
            currentClass.declaredFields.forEach { field ->
                when {
                    field.isAnnotationPresent(Version::class.java) && version == null -> {
                        version = FieldAccessor(field)
                    }

                    field.isIdentifier() -> identifiers += FieldAccessor(field)
                }
            }
            currentClass.declaredMethods
                .filter { it.parameterCount == 0 }
                .forEach { method ->
                    when {
                        method.isAnnotationPresent(Version::class.java) && version == null -> {
                            version = MethodAccessor(method)
                        }

                        method.isIdentifier() -> identifiers += MethodAccessor(method)
                    }
                }
            currentClass = currentClass.superclass
        }

        check(identifiers.isNotEmpty()) {
            "Cannot determine whether ${entityClass.name} is new because it has no " +
                    "@Id or @EmbeddedId field/getter"
        }

        return EntityStateMetadata(identifiers, version)
    }

    private fun Field.isIdentifier(): Boolean =
        isAnnotationPresent(Id::class.java) || isAnnotationPresent(EmbeddedId::class.java)

    private fun Method.isIdentifier(): Boolean =
        isAnnotationPresent(Id::class.java) || isAnnotationPresent(EmbeddedId::class.java)

    private data class EntityStateMetadata(
        val identifiers: List<ValueAccessor>,
        val version: ValueAccessor?,
    ) {
        fun isNew(entity: Any): Boolean {
            version
                ?.takeUnless { it.javaType.isPrimitive }
                ?.let { return it.read(entity) == null }

            return identifiers.any { identifier ->
                val value = identifier.read(entity)
                value == null || identifier.javaType.isPrimitiveDefault(value)
            }
        }
    }

    private sealed interface ValueAccessor {
        val javaType: Class<*>

        fun read(entity: Any): Any?
    }

    private class FieldAccessor(
        private val field: Field,
    ) : ValueAccessor {
        init {
            check(field.trySetAccessible()) {
                "Cannot access persistent field ${field.declaringClass.name}.${field.name}"
            }
        }

        override val javaType: Class<*> = field.type

        override fun read(entity: Any): Any? = field.get(entity)
    }

    private class MethodAccessor(
        private val method: Method,
    ) : ValueAccessor {
        init {
            check(method.trySetAccessible()) {
                "Cannot access persistent getter ${method.declaringClass.name}.${method.name}"
            }
        }

        override val javaType: Class<*> = method.returnType

        override fun read(entity: Any): Any? = method.invoke(entity)
    }

    private fun Class<*>.isPrimitiveDefault(value: Any): Boolean {
        if (!isPrimitive) return false
        return when (value) {
            is Number -> value.toDouble() == 0.0
            is Char -> value == '\u0000'
            is Boolean -> !value
            else -> false
        }
    }
}
