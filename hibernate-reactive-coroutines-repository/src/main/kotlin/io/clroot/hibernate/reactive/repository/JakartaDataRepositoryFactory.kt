package io.clroot.hibernate.reactive.repository

import io.clroot.hibernate.reactive.ReactiveSessionOperations
import io.clroot.hibernate.reactive.repository.runtime.RepositoryEntityLifecycle
import io.clroot.hibernate.reactive.repository.runtime.RepositoryFactory
import jakarta.persistence.metamodel.Metamodel

/** Creates non-Spring coroutine repositories from HRC query metadata and Jakarta Data paging types. */
public class JakartaDataRepositoryFactory(
    sessionOperations: ReactiveSessionOperations,
    metamodel: Metamodel,
    entityLifecycle: RepositoryEntityLifecycle = RepositoryEntityLifecycle.NONE,
) {
    private val delegate = RepositoryFactory(
        sessionOperations = sessionOperations,
        metamodel = metamodel,
        runtimeAdapter = JakartaDataRepositoryRuntimeAdapter,
        entityLifecycle = entityLifecycle,
    )

    /** Parses [repositoryInterface] once and creates a proxy backed by the shared runtime. */
    public fun <T : Any, ID : Any, R : CoroutineCrudRepository<T, ID>> create(
        repositoryInterface: Class<R>,
        entityClass: Class<T>,
        idClass: Class<ID>,
        entityName: String = entityClass.simpleName,
    ): R {
        require(repositoryInterface.isInterface) { "Repository type must be an interface: ${repositoryInterface.name}" }
        require(CoroutineCrudRepository::class.java.isAssignableFrom(repositoryInterface)) {
            "Repository interface must extend CoroutineCrudRepository: ${repositoryInterface.name}"
        }
        val queryMethods = JakartaDataQueryMethodParser(entityClass, entityName)
            .parseRepository(repositoryInterface)
        return delegate.create(
            repositoryInterface = repositoryInterface,
            entityClass = entityClass,
            idClass = idClass,
            entityName = entityName,
            queryMethods = queryMethods,
        )
    }
}
