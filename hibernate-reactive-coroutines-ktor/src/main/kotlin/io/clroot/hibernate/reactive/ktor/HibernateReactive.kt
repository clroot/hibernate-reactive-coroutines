package io.clroot.hibernate.reactive.ktor

import io.clroot.hibernate.reactive.ReactiveSessionProvider
import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.repository.CoroutineCrudRepository
import io.clroot.hibernate.reactive.repository.JakartaDataRepositoryFactory
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.log
import io.vertx.core.Vertx
import jakarta.persistence.Entity
import org.hibernate.cfg.AvailableSettings
import org.hibernate.cfg.Configuration
import org.hibernate.reactive.common.spi.Implementor
import org.hibernate.reactive.mutiny.Mutiny
import org.hibernate.reactive.provider.ReactiveServiceRegistryBuilder
import org.hibernate.reactive.vertx.VertxInstance
import org.hibernate.reactive.vertx.impl.ProvidedVertxInstance

/**
 * Ktor plugin that bootstraps Hibernate Reactive and explicitly registered coroutine repositories.
 *
 * The plugin does not open a request-wide session or transaction. Handlers and services should use
 * [HibernateReactiveResources.transactionExecutor] to define transaction boundaries explicitly.
 * Resources and repositories can be published through the optional Ktor dependency injection module.
 */
public val HibernateReactive: ApplicationPlugin<HibernateReactiveConfiguration> =
    createApplicationPlugin(
        name = "HibernateReactive",
        createConfiguration = ::HibernateReactiveConfiguration,
    ) {
        val resources = createResources(pluginConfig)
        application.attributes.put(HibernateReactiveResourcesKey, resources)

        try {
            if (pluginConfig.dependencyInjection) {
                KtorDependencyInjectionBridge.install(application, resources)
            }
        } catch (exception: NoClassDefFoundError) {
            resources.close()
            application.attributes.remove(HibernateReactiveResourcesKey)
            throw IllegalStateException(
                "Ktor dependency injection was requested, but ktor-server-di is not on the runtime classpath",
                exception,
            )
        } catch (exception: Throwable) {
            resources.close()
            application.attributes.remove(HibernateReactiveResourcesKey)
            throw exception
        }

        on(MonitoringEvent(ApplicationStopping)) { stoppingApplication ->
            try {
                stoppingApplication.attributes
                    .getOrNull(HibernateReactiveResourcesKey)
                    ?.close()
            } catch (exception: Throwable) {
                stoppingApplication.log.error("Failed to close Hibernate Reactive resources", exception)
            }
        }
    }

private fun createResources(config: HibernateReactiveConfiguration): HibernateReactiveResources {
    val externalSessionFactory = config.sessionFactory
    val configuredVertx = config.vertx
    val discoveredVertx = externalSessionFactory?.let(::discoverVertx)
    require(configuredVertx == null || discoveredVertx == null || configuredVertx === discoveredVertx) {
        "The configured Vert.x instance must be the instance used by the external session factory"
    }
    val ownsVertx = configuredVertx == null && externalSessionFactory == null
    val vertx = configuredVertx
        ?: discoveredVertx
        ?: if (externalSessionFactory == null) {
            Vertx.vertx()
        } else {
            throw IllegalStateException(
                "Could not discover the Vert.x instance used by the external session factory. " +
                    "Configure vertx explicitly with the same instance.",
            )
        }

    val ownsSessionFactory = externalSessionFactory == null
    var sessionFactory: Mutiny.SessionFactory? = externalSessionFactory

    try {
        if (sessionFactory == null) {
            sessionFactory = bootstrapSessionFactory(config, vertx)
        }

        val sessionProvider = ReactiveSessionProvider(sessionFactory)
        val transactionExecutor = ReactiveTransactionExecutor(sessionFactory)
        val repositories = createRepositories(config, sessionProvider, sessionFactory)

        return HibernateReactiveResources(
            sessionFactory = sessionFactory,
            sessionProvider = sessionProvider,
            transactionExecutor = transactionExecutor,
            vertx = vertx,
            repositories = repositories,
            closeSessionFactory = ownsSessionFactory || config.closeExternalSessionFactory,
            closeVertx = ownsVertx || config.closeExternalVertx,
        )
    } catch (exception: Throwable) {
        if (ownsSessionFactory) {
            runCatching { sessionFactory?.close() }
                .exceptionOrNull()
                ?.let(exception::addSuppressed)
        }
        if (ownsVertx) {
            runCatching(vertx::closeBlocking)
                .exceptionOrNull()
                ?.let(exception::addSuppressed)
        }
        throw exception
    }
}

private fun bootstrapSessionFactory(
    config: HibernateReactiveConfiguration,
    vertx: Vertx,
): Mutiny.SessionFactory {
    val database = config.databaseConfiguration
    require(database.poolSize > 0) { "Hibernate Reactive pool size must be greater than zero" }

    val configuredUrl = database.url
        ?: database.properties[AvailableSettings.JAKARTA_JDBC_URL]?.toString()
        ?: throw IllegalStateException(
            "A database URL is required when HibernateReactive creates the session factory",
        )
    val reactiveUrl = configuredUrl.removePrefix("jdbc:")

    val configuration = Configuration()
        .setProperty(AvailableSettings.JAKARTA_JDBC_URL, reactiveUrl)
        .setProperty(AvailableSettings.HBM2DDL_AUTO, database.schemaGeneration)
        .setProperty(AvailableSettings.SHOW_SQL, database.showSql.toString())
        .setProperty("hibernate.connection.pool_size", database.poolSize.toString())

    database.username?.let { username ->
        configuration.setProperty(AvailableSettings.JAKARTA_JDBC_USER, username)
    }
    database.password?.let { password ->
        configuration.setProperty(AvailableSettings.JAKARTA_JDBC_PASSWORD, password)
    }
    database.properties.forEach { (name, value) ->
        if (name != AvailableSettings.JAKARTA_JDBC_URL) {
            configuration.properties[name] = value
        }
    }
    config.entityClasses.forEach(configuration::addAnnotatedClass)

    val serviceRegistry = ReactiveServiceRegistryBuilder()
        .applySettings(configuration.properties)
        .addService(VertxInstance::class.java, ProvidedVertxInstance(vertx))
        .build()

    val ormSessionFactory = try {
        configuration.buildSessionFactory(serviceRegistry)
    } catch (exception: Throwable) {
        ReactiveServiceRegistryBuilder.destroy(serviceRegistry)
        throw exception
    }
    return try {
        ormSessionFactory.unwrap(Mutiny.SessionFactory::class.java)
    } catch (exception: Throwable) {
        runCatching(ormSessionFactory::close)
            .exceptionOrNull()
            ?.let(exception::addSuppressed)
        throw exception
    }
}

private fun createRepositories(
    config: HibernateReactiveConfiguration,
    sessionProvider: ReactiveSessionProvider,
    sessionFactory: Mutiny.SessionFactory,
): Map<Class<*>, Any> {
    if (config.repositoryRegistrations.isEmpty()) return emptyMap()

    val factory = JakartaDataRepositoryFactory(
        sessionOperations = sessionProvider,
        metamodel = sessionFactory.metamodel,
    )
    return config.repositoryRegistrations.values.associate { registration ->
        registration.repositoryInterface to createRepository(factory, registration)
    }
}

@Suppress("UNCHECKED_CAST")
private fun createRepository(
    factory: JakartaDataRepositoryFactory,
    registration: RepositoryRegistration,
): Any {
    val entityName = registration.entityName
        ?: registration.entityClass.getAnnotation(Entity::class.java)
            ?.name
            ?.takeIf(String::isNotBlank)
        ?: registration.entityClass.simpleName
    return factory.create(
        repositoryInterface = registration.repositoryInterface as Class<CoroutineCrudRepository<Any, Any>>,
        entityClass = registration.entityClass as Class<Any>,
        idClass = registration.idClass as Class<Any>,
        entityName = entityName,
    )
}

private fun discoverVertx(sessionFactory: Mutiny.SessionFactory): Vertx? =
    (sessionFactory as? Implementor)
        ?.serviceRegistry
        ?.getService(VertxInstance::class.java)
        ?.vertx
