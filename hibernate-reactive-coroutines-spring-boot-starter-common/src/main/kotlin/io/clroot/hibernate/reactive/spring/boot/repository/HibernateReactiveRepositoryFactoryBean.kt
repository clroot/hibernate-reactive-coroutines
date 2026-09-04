package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.repository.runtime.RepositoryFactory
import io.clroot.hibernate.reactive.spring.boot.auditing.ReactiveAuditingHandler
import io.clroot.hibernate.reactive.spring.boot.repository.query.PreparedQueryMethod
import io.clroot.hibernate.reactive.spring.boot.repository.query.toRuntimeQuery
import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import org.springframework.beans.factory.FactoryBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.GenericTypeResolver
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.lang.reflect.Method

/**
 * FactoryBean that creates [CoroutineCrudRepository] proxies.
 *
 * Custom query methods are parsed at startup and cached as [PreparedQueryMethod] instances.
 *
 * @param T Repository interface type.
 * @param repositoryInterface Repository interface class.
 */
public class HibernateReactiveRepositoryFactoryBean<T : CoroutineCrudRepository<*, *>>(
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
        val declaredMethods = repositoryInterface.methods
            .filter { parser.isDeclaredRepositoryMethod(it) }

        rejectNonSuspendMethods(parser, declaredMethods)

        val queryMethods = declaredMethods.filter { parser.isSuspendMethod(it) }
        rejectAmbiguousOverloads(parser, queryMethods)

        return queryMethods.associate { method ->
            parser.createMethodKey(method) to parser.parse(method)
        }
    }

    private fun rejectNonSuspendMethods(parser: QueryMethodParser, methods: List<Method>) {
        val offender = methods.firstOrNull { !parser.isSuspendMethod(it) } ?: return

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
    private fun rejectAmbiguousOverloads(parser: QueryMethodParser, methods: List<Method>) {
        methods
            .groupBy { parser.createMethodKey(it) }
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
