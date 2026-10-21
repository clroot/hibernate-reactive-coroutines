package io.clroot.hibernate.reactive.repository.runtime

import io.clroot.hibernate.reactive.InternalHrcApi
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation

/**
 * Selects and validates the custom query methods of a repository interface at startup.
 *
 * Every integration routes through this scanner so that declarations the runtime cannot dispatch
 * fail when the repository is created instead of on first invocation.
 */
@InternalHrcApi
public object RepositoryMethodScanner {
    private val BASE_METHODS = setOf(
        "save", "saveAll", "findById", "findAll", "findAllById", "existsById", "count",
        "deleteById", "delete", "deleteAllById", "deleteAll",
    )

    /**
     * Returns the user-declared query methods of [repositoryInterface].
     *
     * @throws IllegalStateException if a declared method is not a suspend function or if two methods
     * share the same [methodKey].
     */
    public fun queryMethods(repositoryInterface: Class<*>): List<Method> {
        val declaredMethods = repositoryInterface.methods.filter(::isDeclaredRepositoryMethod)
        rejectNonSuspendMethods(repositoryInterface, declaredMethods)
        rejectAmbiguousOverloads(repositoryInterface, declaredMethods)
        return declaredMethods
    }

    /** Runtime lookup key: the method name and its argument count, excluding the [Continuation]. */
    public fun methodKey(method: Method): String = "${method.name}#${method.parameterTypes.size - 1}"

    /** Excludes inherited CRUD and `Object` methods plus compiler-generated and non-abstract methods. */
    public fun isDeclaredRepositoryMethod(method: Method): Boolean {
        if (method.name in BASE_METHODS) return false
        if (method.declaringClass == Any::class.java) return false
        if (method.isDefault || method.isSynthetic || method.isBridge) return false
        return !Modifier.isStatic(method.modifiers)
    }

    /** Kotlin compiles suspend functions with a trailing [Continuation] parameter. */
    public fun isSuspendMethod(method: Method): Boolean =
        method.parameterTypes.lastOrNull()?.let(Continuation::class.java::isAssignableFrom) == true

    private fun rejectNonSuspendMethods(repositoryInterface: Class<*>, methods: List<Method>) {
        val offender = methods.firstOrNull { !isSuspendMethod(it) } ?: return
        throw IllegalStateException(
            "Repository method '${repositoryInterface.name}.${offender.name}' must be a suspend function. " +
                "Non-suspend query methods (including Flow-returning ones) are not supported; " +
                "declare it as 'suspend fun ${offender.name}(...): List<T>' instead.",
        )
    }

    /**
     * Runtime lookup keys contain only the method name and argument count, so overloads with the same key
     * cannot be selected deterministically.
     */
    private fun rejectAmbiguousOverloads(repositoryInterface: Class<*>, methods: List<Method>) {
        methods
            .groupBy(::methodKey)
            .forEach { (key, overloads) ->
                val distinctSignatures = overloads.distinctBy { it.parameterTypes.toList() }
                if (distinctSignatures.size > 1) {
                    val signatures = distinctSignatures.joinToString(", ") { method ->
                        method.parameterTypes.dropLast(1).joinToString(", ") { it.simpleName }
                    }
                    throw IllegalStateException(
                        "Repository '${repositoryInterface.name}' declares ambiguous overloads for '$key': " +
                            "[$signatures]. Query methods are resolved by name and argument count, " +
                            "so overloads with the same argument count are not supported.",
                    )
                }
            }
    }
}
