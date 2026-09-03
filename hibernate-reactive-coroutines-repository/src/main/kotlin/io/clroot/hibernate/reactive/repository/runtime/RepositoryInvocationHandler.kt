package io.clroot.hibernate.reactive.repository.runtime

import io.clroot.hibernate.reactive.ReactiveSessionOperations
import jakarta.persistence.metamodel.Metamodel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine

/** Framework-neutral invocation handler shared by repository integrations. */
public class RepositoryInvocationHandler<T : Any, ID : Any>(
    private val entityClass: Class<T>,
    @Suppress("UNUSED_PARAMETER") idClass: Class<ID>,
    sessionOperations: ReactiveSessionOperations,
    metamodel: Metamodel,
    private val queryMethods: Map<String, PreparedRepositoryQuery> = emptyMap(),
    private val runtimeAdapter: RepositoryRuntimeAdapter = RepositoryRuntimeAdapter.DEFAULT,
    entityLifecycle: RepositoryEntityLifecycle = RepositoryEntityLifecycle.NONE,
    entityName: String = entityClass.simpleName,
) : InvocationHandler {
    private val crud = CrudOperations<T, ID>(entityClass, entityName, sessionOperations, entityLifecycle)
    private val query = QueryOperations<T>(entityClass, sessionOperations, metamodel)
    private val pagination = PaginationOperations(
        entityClass,
        entityName,
        sessionOperations,
        query,
        runtimeAdapter,
    )

    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any {
        when (method.name) {
            "toString" -> return "${entityClass.simpleName}Repository(proxy)"
            "hashCode" -> return System.identityHashCode(proxy)
            "equals" -> return proxy === args?.firstOrNull()
        }

        @Suppress("UNCHECKED_CAST")
        val continuation = args?.lastOrNull() as? Continuation<Any?>
        if (continuation == null && method.name in FLOW_METHODS) {
            return invokeFlowMethod(method.name, args?.toList() ?: emptyList())
        }
        if (continuation == null) {
            throw IllegalStateException("Expected suspend function but no Continuation found")
        }

        val actualArgs = if (args.size > 1) args.dropLast(1) else emptyList()
        return invokeSuspend(method.name, actualArgs, continuation)
    }

    private fun invokeFlowMethod(methodName: String, args: List<Any?>): Flow<*> {
        @Suppress("UNCHECKED_CAST")
        return when (methodName) {
            "findAll" -> crud.findAll()
            "findAllById" -> when (val ids = args.firstOrNull()) {
                is Iterable<*> -> crud.findAllById(ids as Iterable<ID>)
                is Flow<*> -> crud.findAllByIdFlow(ids as Flow<ID>)
                else -> throw IllegalArgumentException("findAllById requires Iterable or Flow parameter")
            }
            "saveAll" -> when (val entities = args.firstOrNull()) {
                is Iterable<*> -> crud.saveAll(entities as Iterable<T>)
                is Flow<*> -> crud.saveAllFlow(entities as Flow<T>)
                else -> throw IllegalArgumentException("saveAll requires Iterable or Flow parameter")
            }
            else -> throw UnsupportedOperationException("Unknown flow method: $methodName")
        }
    }

    private fun invokeSuspend(
        methodName: String,
        args: List<Any?>,
        continuation: Continuation<Any?>,
    ): Any {
        val operation: suspend () -> Any? = { routeSuspendMethod(methodName, args) }
        operation.startCoroutine(continuation)
        return COROUTINE_SUSPENDED
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun routeSuspendMethod(methodName: String, args: List<Any?>): Any? = when (methodName) {
        "save" -> crud.save(args[0] as T)
        "findById" -> crud.findById(args[0] as ID)
        "deleteById" -> crud.deleteById(args[0] as ID)
        "delete" -> crud.delete(args[0] as T)
        "count" -> crud.count()
        "existsById" -> crud.existsById(args[0] as ID)
        "deleteAllById" -> crud.deleteAllById(args[0] as Iterable<ID>)
        "deleteAll" -> routeDeleteAll(args)
        "findAll" -> routeFindAll(args)
        else -> routeCustomQueryMethod(methodName, args)
    }

    private suspend fun routeDeleteAll(args: List<Any?>) {
        @Suppress("UNCHECKED_CAST")
        when {
            args.isEmpty() -> crud.deleteAll()
            args[0] is Iterable<*> -> crud.deleteAllEntities(args[0] as Iterable<T>)
            args[0] is Flow<*> -> crud.deleteAllFlow(args[0] as Flow<T>)
            else -> throw IllegalArgumentException("Invalid argument for deleteAll")
        }
    }

    private suspend fun routeFindAll(args: List<Any?>): Any {
        val adapted = runtimeAdapter.adaptArguments(args)
        return when {
            adapted.queryArguments.isEmpty() && adapted.pageRequest != null ->
                pagination.findAllWithPageRequest(adapted.pageRequest)
            adapted.queryArguments.isEmpty() && adapted.hasSortParameter ->
                pagination.findAllWithSort(adapted.sort)
            args.isEmpty() -> crud.findAll().toList()
            else -> throw IllegalArgumentException("Invalid argument for findAll")
        }
    }

    private suspend fun routeCustomQueryMethod(methodName: String, args: List<Any?>): Any? {
        val methodKey = "$methodName#${args.size}"
        val prepared = queryMethods[methodKey]
            ?: throw UnsupportedOperationException(
                MethodSuggestionHelper.buildUnknownMethodError(methodName, BASE_METHODS + queryMethods.keys),
            )
        return executeQueryMethod(prepared, runtimeAdapter.adaptArguments(args))
    }

    private suspend fun executeQueryMethod(
        prepared: PreparedRepositoryQuery,
        invocation: RepositoryInvocationArguments,
    ): Any? {
        val args = invocation.queryArguments
        if (prepared.queryKind == RepositoryQueryKind.ANNOTATED) {
            return executeAnnotatedQuery(prepared, args, invocation)
        }

        val boundArgs = args.mapIndexed { index, arg ->
            prepared.parameterBindings.getOrNull(index)?.bind(arg) ?: arg
        }

        if (prepared.isDelete) {
            val deletedCount = query.executeDeleteQuery(prepared.hql, boundArgs)
            return when (prepared.returnType) {
                RepositoryQueryReturnType.MODIFYING -> deletedCount.toInt()
                RepositoryQueryReturnType.LONG -> deletedCount
                else -> Unit
            }
        }

        return when (prepared.returnType) {
            RepositoryQueryReturnType.SINGLE ->
                query.executeSingleQuery(prepared.hql, boundArgs, prepared.maxResults)
            RepositoryQueryReturnType.LIST -> if (invocation.sort.isNotEmpty()) {
                query.executeListQueryWithSort(prepared, boundArgs, invocation.sort)
            } else {
                query.executeListQuery(prepared.hql, boundArgs, prepared.maxResults)
            }
            RepositoryQueryReturnType.BOOLEAN -> query.executeExistsQuery(prepared.hql, boundArgs)
            RepositoryQueryReturnType.LONG -> query.executeCountQuery(prepared.hql, boundArgs)
            RepositoryQueryReturnType.VOID -> throw IllegalStateException(
                "VOID return type is only produced by derived delete methods",
            )
            RepositoryQueryReturnType.PAGE -> pagination.executePageQuery(
                prepared,
                boundArgs,
                checkNotNull(invocation.pageRequest),
            )
            RepositoryQueryReturnType.SLICE -> pagination.executeSliceQuery(
                prepared,
                boundArgs,
                checkNotNull(invocation.pageRequest),
            )
            RepositoryQueryReturnType.MODIFYING -> throw IllegalStateException(
                "MODIFYING should be handled by executeAnnotatedQuery",
            )
        }
    }

    private suspend fun executeAnnotatedQuery(
        prepared: PreparedRepositoryQuery,
        args: List<Any?>,
        invocation: RepositoryInvocationArguments,
    ): Any? = when (prepared.returnType) {
        RepositoryQueryReturnType.MODIFYING -> query.executeModifyingAnnotatedQuery(prepared, args)
        RepositoryQueryReturnType.VOID -> {
            query.executeModifyingAnnotatedQuery(prepared, args)
            Unit
        }
        RepositoryQueryReturnType.PAGE -> pagination.executePageAnnotatedQuery(
            prepared,
            args,
            checkNotNull(invocation.pageRequest),
        )
        RepositoryQueryReturnType.SLICE -> pagination.executeSliceAnnotatedQuery(
            prepared,
            args,
            checkNotNull(invocation.pageRequest),
        )
        RepositoryQueryReturnType.LIST -> query.executeListAnnotatedQuery(prepared, args, invocation.sort)
        RepositoryQueryReturnType.SINGLE -> query.executeSingleAnnotatedQuery(prepared, args)
        else -> throw IllegalStateException("Unsupported return type for @Query: ${prepared.returnType}")
    }

    public companion object {
        private val BASE_METHODS = setOf(
            "save", "saveAll", "findById", "findAll", "findAllById",
            "existsById", "count", "deleteById", "delete", "deleteAllById", "deleteAll",
        )
        private val FLOW_METHODS = setOf("findAll", "findAllById", "saveAll")
    }
}
