package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.repository.runtime.RepositoryInvocationHandler
import io.clroot.hibernate.reactive.spring.boot.auditing.ReactiveAuditingHandler
import io.clroot.hibernate.reactive.spring.boot.repository.query.PreparedQueryMethod
import io.clroot.hibernate.reactive.spring.boot.repository.query.toRuntimeQuery
import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

/**
 * Backward-compatible Spring-facing invocation handler.
 *
 * Runtime dispatch, CRUD, query, and pagination execution are delegated to the framework-neutral
 * repository module. The constructor remains unchanged for source and binary compatibility;
 * [transactionExecutor] is retained for that compatibility even though writes now use the shared
 * session-operation SPI directly.
 */
public class SimpleHibernateReactiveRepository<T : Any, ID : Any>(
    entityClass: Class<T>,
    idClass: Class<ID>,
    sessionProvider: TransactionalAwareSessionProvider,
    @Suppress("UNUSED_PARAMETER") transactionExecutor: ReactiveTransactionExecutor,
    queryMethods: Map<String, PreparedQueryMethod> = emptyMap(),
    auditingHandler: ReactiveAuditingHandler<*>? = null,
    entityName: String = entityClass.simpleName,
) : InvocationHandler {
    public companion object {}

    private val delegate = RepositoryInvocationHandler(
        entityClass = entityClass,
        idClass = idClass,
        sessionOperations = sessionProvider,
        metamodel = sessionProvider.metamodel,
        queryMethods = queryMethods.mapValues { (_, prepared) -> prepared.toRuntimeQuery() },
        runtimeAdapter = SpringRepositoryRuntimeAdapter,
        entityLifecycle = SpringRepositoryEntityLifecycle(auditingHandler),
        entityName = entityName,
    )

    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any =
        delegate.invoke(proxy, method, args)
}
