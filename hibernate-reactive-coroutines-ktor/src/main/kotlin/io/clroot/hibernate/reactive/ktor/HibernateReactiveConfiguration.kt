package io.clroot.hibernate.reactive.ktor

import io.clroot.hibernate.reactive.repository.CoroutineCrudRepository
import io.ktor.utils.io.KtorDsl
import io.vertx.core.Vertx
import org.hibernate.reactive.mutiny.Mutiny
import kotlin.jvm.javaObjectType
import kotlin.reflect.KClass

/** Database and Hibernate settings used when the plugin creates the session factory. */
@KtorDsl
public class HibernateReactiveDatabaseConfiguration {
    /** Reactive or JDBC-style connection URL. JDBC URLs are converted by removing the `jdbc:` prefix. */
    public var url: String? = null

    /** Database user name. */
    public var username: String? = null

    /** Database password. */
    public var password: String? = null

    /** Hibernate schema-management action, such as `validate`, `update`, or `create-drop`. */
    public var schemaGeneration: String = "none"

    /** Hibernate Reactive connection pool size. */
    public var poolSize: Int = 10

    /** Whether Hibernate should log SQL statements. */
    public var showSql: Boolean = false

    /** Additional Hibernate settings passed to the reactive service registry. */
    public val properties: MutableMap<String, Any> = linkedMapOf()

    /** Adds or replaces an arbitrary Hibernate setting. */
    public fun property(name: String, value: Any) {
        require(name.isNotBlank()) { "Hibernate property name must not be blank" }
        properties[name] = value
    }
}

/** Configuration DSL for [HibernateReactive]. */
@KtorDsl
public class HibernateReactiveConfiguration {
    /** Externally managed Vert.x instance. The plugin creates one when bootstrapping if this is null. */
    public var vertx: Vertx? = null

    /** Externally managed session factory. When set, database bootstrap settings are ignored. */
    public var sessionFactory: Mutiny.SessionFactory? = null

    /** Whether to close an externally supplied or discovered Vert.x instance on shutdown. */
    public var closeExternalVertx: Boolean = false

    /** Whether to close an externally supplied session factory on shutdown. */
    public var closeExternalSessionFactory: Boolean = false

    /** Whether to expose plugin resources and repositories through optional Ktor dependency injection. */
    public var dependencyInjection: Boolean = false

    internal val databaseConfiguration: HibernateReactiveDatabaseConfiguration =
        HibernateReactiveDatabaseConfiguration()

    internal val entityClasses: MutableSet<Class<*>> = linkedSetOf()

    internal val repositoryRegistrations: MutableMap<Class<*>, RepositoryRegistration> = linkedMapOf()

    /** Configures connection and Hibernate settings for a plugin-created session factory. */
    public fun database(configure: HibernateReactiveDatabaseConfiguration.() -> Unit) {
        databaseConfiguration.configure()
    }

    /** Registers one or more managed entity classes explicitly. */
    public fun entities(vararg entityClasses: KClass<*>) {
        entityClasses.forEach { entityClass -> this.entityClasses += entityClass.java }
    }

    /** Registers a managed entity class explicitly. */
    public inline fun <reified T : Any> entity(): Unit = entities(T::class)

    /**
     * Registers a coroutine repository and its entity/id contract explicitly.
     * The repository's entity is added to the managed entity set automatically.
     */
    public fun <T : Any, ID : Any, R : CoroutineCrudRepository<T, ID>> repository(
        repositoryInterface: KClass<R>,
        entityClass: KClass<T>,
        idClass: KClass<ID>,
        entityName: String? = null,
    ) {
        val repositoryJavaClass = repositoryInterface.java
        require(repositoryJavaClass.isInterface) {
            "Repository type must be an interface: ${repositoryJavaClass.name}"
        }
        require(repositoryRegistrations[repositoryJavaClass] == null) {
            "Repository is already registered: ${repositoryJavaClass.name}"
        }
        entityClasses += entityClass.java
        repositoryRegistrations[repositoryJavaClass] = RepositoryRegistration(
            repositoryInterface = repositoryJavaClass,
            entityClass = entityClass.java,
            idClass = idClass.javaObjectType,
            entityName = entityName,
        )
    }

    /** Reified form of [repository]. */
    public inline fun <
        reified R : CoroutineCrudRepository<T, ID>,
        reified T : Any,
        reified ID : Any,
    > repository(entityName: String? = null): Unit =
        repository(R::class, T::class, ID::class, entityName)
}

internal data class RepositoryRegistration(
    val repositoryInterface: Class<*>,
    val entityClass: Class<*>,
    val idClass: Class<*>,
    val entityName: String?,
)
