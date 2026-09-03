package io.clroot.hibernate.reactive.repository.runtime

import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.jvm.JvmInline

/**
 * Adapts repository identifier values to the JVM type used by Hibernate.
 */
internal object RepositoryIdAdapter {
    private val adapters = ConcurrentHashMap<Class<*>, IdAdapter>()

    fun unwrap(id: Any): Any {
        return adapters.computeIfAbsent(id.javaClass, ::createAdapter).unwrap(id)
    }

    private fun createAdapter(idClass: Class<*>): IdAdapter {
        if (!idClass.isAnnotationPresent(JvmInline::class.java)) {
            return IdentityAdapter
        }

        val unboxMethod = idClass.declaredMethods.singleOrNull {
            it.name == "unbox-impl" && it.parameterCount == 0
        } ?: error("Cannot find value class unbox method for ${idClass.name}")

        check(unboxMethod.trySetAccessible()) {
            "Cannot access value class unbox method for ${idClass.name}"
        }
        return ValueClassAdapter(unboxMethod)
    }

    private fun interface IdAdapter {
        fun unwrap(id: Any): Any
    }

    private object IdentityAdapter : IdAdapter {
        override fun unwrap(id: Any): Any = id
    }

    private class ValueClassAdapter(
        private val unboxMethod: Method,
    ) : IdAdapter {
        override fun unwrap(id: Any): Any {
            return checkNotNull(unboxMethod.invoke(id)) {
                "Value class identifier ${id.javaClass.name} unboxed to null"
            }
        }
    }
}
