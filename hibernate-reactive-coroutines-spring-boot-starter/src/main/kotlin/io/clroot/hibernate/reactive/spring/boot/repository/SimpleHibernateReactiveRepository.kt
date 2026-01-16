package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.spring.boot.auditing.ReactiveAuditingHandler
import io.clroot.hibernate.reactive.spring.boot.repository.query.PreparedQueryMethod
import io.clroot.hibernate.reactive.spring.boot.repository.query.QueryReturnType
import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.future
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.Continuation

/**
 * CoroutineCrudRepository 인터페이스의 기본 구현체.
 *
 * Java Dynamic Proxy의 InvocationHandler로 동작하며,
 * suspend 함수 호출을 Hibernate Reactive Session 작업으로 변환합니다.
 *
 * 실제 작업은 내부 헬퍼 클래스들에게 위임합니다:
 * - [CrudOperations]: 기본 CRUD 작업 (save, find, delete, count)
 * - [QueryOperations]: 쿼리 실행 (PartTree, @Query)
 * - [PaginationOperations]: 페이징 쿼리 (Page, Slice)
 * - [MethodSuggestionHelper]: 유사 메서드 추천
 *
 * @param T 엔티티 타입
 * @param ID 엔티티의 ID 타입
 * @param entityClass 엔티티 클래스
 * @param idClass ID 클래스
 * @param sessionProvider ReactiveSessionProvider 인스턴스
 * @param queryMethods 미리 파싱된 쿼리 메서드 맵
 */
class SimpleHibernateReactiveRepository<T : Any, ID : Any>(
    private val entityClass: Class<T>,
    private val idClass: Class<ID>,
    private val sessionProvider: TransactionalAwareSessionProvider,
    private val transactionExecutor: ReactiveTransactionExecutor,
    private val queryMethods: Map<String, PreparedQueryMethod> = emptyMap(),
    auditingHandler: ReactiveAuditingHandler<*>? = null,
) : InvocationHandler {

    companion object {
        /** CoroutineCrudRepository의 기본 메서드 이름들 */
        private val BASE_METHODS = setOf(
            "save", "saveAll",
            "findById", "findAll", "findAllById",
            "existsById", "count",
            "deleteById", "delete", "deleteAllById", "deleteAll",
        )

        /** Flow를 반환하는 메서드들 */
        private val FLOW_METHODS = setOf("findAll", "findAllById", "saveAll")
    }

    // 내부 헬퍼 클래스들
    private val crud = CrudOperations<T, ID>(entityClass, sessionProvider, transactionExecutor, auditingHandler)
    private val query = QueryOperations<T>(entityClass, sessionProvider)
    private val pagination = PaginationOperations<T>(entityClass, sessionProvider, query)

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    // ============================================
    // InvocationHandler 구현
    // ============================================

    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any {
        // Object 클래스의 기본 메서드 처리
        when (method.name) {
            "toString" -> return "${entityClass.simpleName}Repository(proxy)"
            "hashCode" -> return System.identityHashCode(proxy)
            "equals" -> return proxy === args?.firstOrNull()
        }

        // suspend 함수 감지: 마지막 파라미터가 Continuation인지 확인
        @Suppress("UNCHECKED_CAST")
        val continuation = args?.lastOrNull() as? Continuation<Any?>

        // Flow 반환 메서드 처리 (suspend가 아닌 일반 함수)
        // findAll은 인자가 없을 때만 Flow 반환 (Pageable/Sort 인자가 있으면 suspend)
        if (continuation == null && method.name in FLOW_METHODS) {
            return invokeFlowMethod(method.name, args?.toList() ?: emptyList())
        }

        // suspend 함수 처리
        if (continuation == null) {
            throw IllegalStateException("Expected suspend function but no Continuation found")
        }

        val actualArgs = if (args.size > 1) args.dropLast(1) else emptyList()

        return invokeSuspend(method.name, actualArgs, continuation)
    }

    // ============================================
    // Flow 메서드 라우팅
    // ============================================

    private fun invokeFlowMethod(methodName: String, args: List<Any?>): Flow<*> {
        @Suppress("UNCHECKED_CAST")
        return when (methodName) {
            "findAll" -> crud.findAll()
            "findAllById" -> {
                when (val ids = args.firstOrNull()) {
                    is Iterable<*> -> crud.findAllById(ids as Iterable<ID>)
                    is Flow<*> -> crud.findAllByIdFlow(ids as Flow<ID>)
                    else -> throw IllegalArgumentException("findAllById requires Iterable or Flow parameter")
                }
            }

            "saveAll" -> {
                when (val entities = args.firstOrNull()) {
                    is Iterable<*> -> crud.saveAll(entities as Iterable<T>)
                    is Flow<*> -> crud.saveAllFlow(entities as Flow<T>)
                    else -> throw IllegalArgumentException("saveAll requires Iterable or Flow parameter")
                }
            }

            else -> throw UnsupportedOperationException("Unknown flow method: $methodName")
        }
    }

    // ============================================
    // Suspend 메서드 라우팅
    // ============================================

    private fun invokeSuspend(
        methodName: String,
        args: List<Any?>,
        continuation: Continuation<Any?>,
    ): Any {
        val future: CompletableFuture<Any?> = scope.future(continuation.context) {
            routeSuspendMethod(methodName, args)
        }

        future.whenComplete { result, error ->
            if (error != null) {
                continuation.resumeWith(Result.failure(error))
            } else {
                continuation.resumeWith(Result.success(result))
            }
        }

        return kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun routeSuspendMethod(methodName: String, args: List<Any?>): Any? {
        return when (methodName) {
            // CRUD 메서드
            "save" -> crud.save(args[0] as T)
            "findById" -> crud.findById(args[0] as ID)
            "deleteById" -> crud.deleteById(args[0] as ID)
            "delete" -> crud.delete(args[0] as T)
            "count" -> crud.count()
            "existsById" -> crud.existsById(args[0] as ID)
            "deleteAllById" -> crud.deleteAllById(args[0] as Iterable<ID>)
            "deleteAll" -> routeDeleteAll(args)
            "findAll" -> routeFindAll(args)

            // 커스텀 쿼리 메서드
            else -> routeCustomQueryMethod(methodName, args)
        }
    }

    private suspend fun routeDeleteAll(args: List<Any?>): Unit {
        @Suppress("UNCHECKED_CAST")
        when {
            args.isEmpty() -> crud.deleteAll()
            args[0] is Iterable<*> -> crud.deleteAllEntities(args[0] as Iterable<T>)
            args[0] is Flow<*> -> crud.deleteAllFlow(args[0] as Flow<T>)
            else -> throw IllegalArgumentException("Invalid argument for deleteAll")
        }
    }

    private suspend fun routeFindAll(args: List<Any?>): Any {
        return when {
            args.isEmpty() -> crud.findAll().toList()
            args[0] is Pageable -> pagination.findAllWithPageable(args[0] as Pageable)
            args[0] is Sort -> pagination.findAllWithSort(args[0] as Sort)
            else -> throw IllegalArgumentException("Invalid argument for findAll")
        }
    }

    // ============================================
    // 커스텀 쿼리 메서드 라우팅
    // ============================================

    private suspend fun routeCustomQueryMethod(methodName: String, args: List<Any?>): Any? {
        val methodKey = "$methodName#${args.size}"
        val prepared = queryMethods[methodKey]
            ?: throw UnsupportedOperationException(
                MethodSuggestionHelper.buildUnknownMethodError(methodName, BASE_METHODS + queryMethods.keys)
            )

        return executeQueryMethod(prepared, args)
    }

    private suspend fun executeQueryMethod(
        prepared: PreparedQueryMethod,
        args: List<Any?>,
    ): Any? {
        val (queryArgs, pageable, sort) = extractPagingParams(args)

        // @Query 어노테이션 메서드 처리
        if (prepared.isAnnotatedQuery) {
            return executeAnnotatedQuery(prepared, queryArgs, pageable)
        }

        // PartTree 기반 쿼리 처리
        val boundArgs = queryArgs.mapIndexed { index, arg ->
            prepared.parameterBinders.getOrNull(index)?.bind(arg) ?: arg
        }

        return when (prepared.returnType) {
            QueryReturnType.SINGLE -> query.executeSingleQuery(prepared.hql, boundArgs)
            QueryReturnType.LIST -> {
                if (sort != null) {
                    query.executeListQueryWithSort(prepared, boundArgs, sort)
                } else {
                    query.executeListQuery(prepared.hql, boundArgs)
                }
            }

            QueryReturnType.BOOLEAN -> query.executeExistsQuery(prepared.hql, boundArgs)
            QueryReturnType.LONG -> query.executeCountQuery(prepared.hql, boundArgs)
            QueryReturnType.VOID -> query.executeDeleteQuery(prepared.hql, boundArgs)
            QueryReturnType.PAGE -> pagination.executePageQuery(prepared, boundArgs, pageable!!)
            QueryReturnType.SLICE -> pagination.executeSliceQuery(prepared, boundArgs, pageable!!)
            QueryReturnType.MODIFYING -> throw IllegalStateException("MODIFYING should be handled by executeAnnotatedQuery")
        }
    }

    private suspend fun executeAnnotatedQuery(
        prepared: PreparedQueryMethod,
        args: List<Any?>,
        pageable: Pageable?,
    ): Any? {
        return when (prepared.returnType) {
            QueryReturnType.MODIFYING -> query.executeModifyingAnnotatedQuery(prepared, args)
            QueryReturnType.PAGE -> pagination.executePageAnnotatedQuery(prepared, args, pageable!!)
            QueryReturnType.SLICE -> pagination.executeSliceAnnotatedQuery(prepared, args, pageable!!)
            QueryReturnType.LIST -> query.executeListAnnotatedQuery(prepared, args)
            QueryReturnType.SINGLE -> query.executeSingleAnnotatedQuery(prepared, args)
            else -> throw IllegalStateException("Unsupported return type for @Query: ${prepared.returnType}")
        }
    }

    // ============================================
    // 유틸리티
    // ============================================

    /**
     * 인자 목록에서 Pageable/Sort를 분리합니다.
     */
    private fun extractPagingParams(args: List<Any?>): Triple<List<Any?>, Pageable?, Sort?> {
        if (args.isEmpty()) return Triple(args, null, null)

        val lastArg = args.last()
        return when (lastArg) {
            is Pageable -> Triple(args.dropLast(1), lastArg, lastArg.sort)
            is Sort -> Triple(args.dropLast(1), null, lastArg)
            else -> Triple(args, null, null)
        }
    }
}
