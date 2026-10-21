package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.repository.runtime.RepositoryFactory
import io.clroot.hibernate.reactive.repository.runtime.RepositoryMethodScanner
import io.clroot.hibernate.reactive.spring.boot.auditing.ReactiveAuditingHandler
import io.clroot.hibernate.reactive.spring.boot.repository.query.PreparedQueryMethod
import io.clroot.hibernate.reactive.spring.boot.repository.query.toRuntimeQuery
import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import org.springframework.beans.factory.FactoryBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.GenericTypeResolver
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * FactoryBean that creates [CoroutineCrudRepository] proxies.
 *
 * Custom query methods are parsed at startup and cached as [PreparedQueryMethod] instances.
 *
 * @param T Repository interface type.
 * @param repositoryInterface Repository interface class.
 */
internal class HibernateReactiveRepositoryFactoryBean<T : CoroutineCrudRepository<*, *>>(
    private val repositoryInterface: Class<T>,
) : FactoryBean<T> {

    @Autowired
    public lateinit var sessionProvider: TransactionalAwareSessionProvider

    @Autowired
    public lateinit var transactionExecutor: ReactiveTransactionExecutor

    @Autowired(required = false)
    public var auditingHandler: ReactiveAuditingHandler<*>? = null

    @Suppress("UNCHECKED_CAST")
    override fun getObject(): T {
        val (entityClass, idClass) = extractGenericTypes(repositoryInterface)
        val entityName = resolveEntityName(entityClass)

        val queryMethods = parseQueryMethods(entityClass, entityName)

        return RepositoryFactory(
            sessionOperations = sessionProvider,
            metamodel = sessionProvider.metamodel,
            runtimeAdapter = SpringRepositoryRuntimeAdapter,
            entityLifecycle = SpringRepositoryEntityLifecycle(auditingHandler),
        ).create(
            repositoryInterface = repositoryInterface,
            entityClass = entityClass as Class<Any>,
            idClass = idClass as Class<Any>,
            entityName = entityName,
            queryMethods = queryMethods.mapValues { (_, prepared) -> prepared.toRuntimeQuery() },
        )
    }

    override fun getObjectType(): Class<*> = repositoryInterface

    override fun isSingleton(): Boolean = true

    /**
     * Extracts the entity and ID types from a repository interface.
     *
     * [GenericTypeResolver] resolves types through inherited generic interfaces.
     */
    private fun extractGenericTypes(repoInterface: Class<*>): Pair<Class<*>, Class<*>> {
        val types = GenericTypeResolver.resolveTypeArguments(
            repoInterface,
            CoroutineCrudRepository::class.java,
        )

        if (types == null || types.size < 2) {
            throw IllegalArgumentException(
                "Cannot extract generic types from ${repoInterface.name}. " +
                        "Make sure it extends CoroutineCrudRepository<T, ID>",
            )
        }

        return types[0] to types[1]
    }

    /**
     * Resolves the entity name from the JPA metamodel for use in HQL.
     *
     * A class's simple name is not reliable when `@Entity(name = "...")` renames it or multiple packages
     * contain entities with the same simple name.
     */
    private fun resolveEntityName(entityClass: Class<*>): String =
        try {
            sessionProvider.metamodel.entity(entityClass).name
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException(
                "${entityClass.name} is not a managed entity. " +
                        "Repository '${repositoryInterface.name}' requires its entity type to be registered " +
                        "with the Hibernate Reactive session factory.",
                e,
            )
        }

    private fun parseQueryMethods(
        entityClass: Class<*>,
        entityName: String,
    ): Map<String, PreparedQueryMethod> {
        val parser = QueryMethodParser(entityClass, entityName)
        return RepositoryMethodScanner.queryMethods(repositoryInterface).associate { method ->
            RepositoryMethodScanner.methodKey(method) to parser.parse(method)
        }
    }
}
