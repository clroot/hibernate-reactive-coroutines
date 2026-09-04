package io.clroot.hibernate.reactive.repository.runtime

import io.clroot.hibernate.reactive.InternalHrcApi
import io.clroot.hibernate.reactive.ReactiveSessionOperations
import jakarta.persistence.metamodel.Metamodel
import java.lang.reflect.Proxy

/** Creates JDK repository proxies without requiring an application framework container. */
@InternalHrcApi
public class RepositoryFactory(
    private val sessionOperations: ReactiveSessionOperations,
    private val metamodel: Metamodel,
    private val runtimeAdapter: RepositoryRuntimeAdapter = RepositoryRuntimeAdapter.DEFAULT,
    private val entityLifecycle: RepositoryEntityLifecycle = RepositoryEntityLifecycle.NONE,
) {
    /** Creates a proxy backed by the shared CRUD and query execution runtime. */
    @Suppress("UNCHECKED_CAST")
    public fun <R : Any, T : Any, ID : Any> create(
        repositoryInterface: Class<R>,
        entityClass: Class<T>,
        idClass: Class<ID>,
        entityName: String = entityClass.simpleName,
        queryMethods: Map<String, PreparedRepositoryQuery> = emptyMap(),
    ): R {
        val handler = RepositoryInvocationHandler(
            entityClass = entityClass,
            idClass = idClass,
            sessionOperations = sessionOperations,
            metamodel = metamodel,
            queryMethods = queryMethods,
            runtimeAdapter = runtimeAdapter,
            entityLifecycle = entityLifecycle,
            entityName = entityName,
        )
        return Proxy.newProxyInstance(
            repositoryInterface.classLoader,
            arrayOf(repositoryInterface),
            handler,
        ) as R
    }
}
