package io.clroot.hibernate.reactive.spring.boot.autoconfigure

import io.clroot.hibernate.reactive.AmbientTransactionProbe
import io.clroot.hibernate.reactive.ReactiveSessionProvider
import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.spring.boot.pool.SslAwareSqlClientPoolConfiguration
import io.clroot.hibernate.reactive.spring.boot.transaction.HibernateReactiveTransactionManager
import io.clroot.hibernate.reactive.spring.boot.transaction.SpringAmbientTransactionProbe
import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.MappedSuperclass
import org.hibernate.cfg.AvailableSettings
import org.hibernate.cfg.Configuration
import org.hibernate.reactive.mutiny.Mutiny
import org.hibernate.reactive.provider.ReactiveServiceRegistryBuilder
import org.hibernate.reactive.vertx.VertxInstance
import org.hibernate.reactive.vertx.impl.ProvidedVertxInstance
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationPackages
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalEventPublisher
import org.springframework.util.ClassUtils

/**
 * Hibernate Reactive Auto-configuration.
 *
 * Registers a [Mutiny.SessionFactory], [ReactiveSessionProvider], [ReactiveTransactionExecutor],
 * and [TransactionalEventPublisher].
 *
 * Managed classes are scanned from the `@SpringBootApplication` packages.
 *
 * Standard connection and JPA options are read from `spring.datasource.*` and `spring.jpa.*`.
 *
 * Hibernate Reactive-specific options are under `spring.jpa.properties.hibernate.reactive.*`;
 * see [HibernateReactiveProperties].
 *
 * The starter creates and exposes a [Vertx] bean, then supplies it to Hibernate Reactive as a
 * [VertxInstance]. An application-provided [Vertx] bean is reused instead.
 *
 * SSL mode precedence is a non-`disable`
 * `spring.jpa.properties.hibernate.reactive.ssl-mode` value, then the JDBC URL's `sslmode`
 * parameter (for example, `?sslmode=require`).
 */
@AutoConfiguration
@ConditionalOnClass(Mutiny.SessionFactory::class)
@EnableConfigurationProperties(HibernateReactiveProperties::class)
internal class HibernateReactiveAutoConfiguration(
    private val applicationContext: ApplicationContext,
    private val properties: HibernateReactiveProperties,
    @Value("\${spring.datasource.url:#{null}}") private val jdbcUrl: String?,
    @Value("\${spring.datasource.username:#{null}}") private val username: String?,
    @Value("\${spring.datasource.password:#{null}}") private val password: String?,
    @Value("\${spring.jpa.database-platform:#{null}}") private val dialect: String?,
    @Value("\${spring.jpa.hibernate.ddl-auto:none}") private val ddlAuto: String,
    @Value("\${spring.jpa.show-sql:false}") private val showSql: Boolean,
    @Value("\${spring.jpa.properties.hibernate.format_sql:false}") private val formatSql: Boolean,
    @Value("\${spring.jpa.properties.hibernate.use_sql_comments:false}") private val useSqlComments: Boolean,
    @Value("\${spring.jpa.properties.hibernate.highlight_sql:false}") private val highlightSql: Boolean,
    @Value("\${spring.jpa.properties.hibernate.default_batch_fetch_size:#{null}}") private val defaultBatchFetchSize: Int?,
    @Value("\${spring.jpa.properties.hibernate.max_fetch_depth:#{null}}") private val maxFetchDepth: Int?,
    @Value("\${spring.jpa.properties.hibernate.jdbc.batch_size:#{null}}") private val jdbcBatchSize: Int?,
    @Value("\${spring.jpa.properties.hibernate.jdbc.fetch_size:#{null}}") private val jdbcFetchSize: Int?,
    @Value("\${spring.jpa.properties.hibernate.order_inserts:false}") private val orderInserts: Boolean,
    @Value("\${spring.jpa.properties.hibernate.order_updates:false}") private val orderUpdates: Boolean,
    @Value("\${spring.jpa.properties.hibernate.jdbc.batch_versioned_data:true}") private val batchVersionedData: Boolean,
    @Value("\${spring.jpa.properties.hibernate.query.plan_cache_max_size:#{null}}") private val queryPlanCacheMaxSize: Int?,
    @Value("\${spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch:false}") private val failOnPaginationOverCollectionFetch: Boolean,
    @Value("\${spring.jpa.properties.hibernate.query.in_clause_parameter_padding:false}") private val inClauseParameterPadding: Boolean,
    @Value("\${spring.jpa.properties.hibernate.globally_quoted_identifiers:false}") private val globallyQuotedIdentifiers: Boolean,
    @Value("\${spring.jpa.properties.hibernate.physical_naming_strategy:#{null}}") private val physicalNamingStrategy: String?,
    @Value("\${spring.jpa.properties.hibernate.implicit_naming_strategy:#{null}}") private val implicitNamingStrategy: String?,
    @Value("\${spring.jpa.properties.hibernate.jdbc.time_zone:#{null}}") private val jdbcTimeZone: String?,
    @Value("\${spring.jpa.properties.hibernate.generate_statistics:false}") private val generateStatistics: Boolean,
    @Value("\${spring.jpa.properties.hibernate.cache.use_second_level_cache:false}") private val useSecondLevelCache: Boolean,
    @Value("\${spring.jpa.properties.hibernate.cache.use_query_cache:false}") private val useQueryCache: Boolean,
) {
    /**
     * Vert.x instance used by Hibernate Reactive.
     *
     * Backs off for an application-provided [Vertx] bean. A starter-created instance is closed
     * when the context stops, after the dependent session factory.
     */
    @Bean
    @ConditionalOnMissingBean(
        value = [Vertx::class, Mutiny.SessionFactory::class],
        name = ["hibernateSessionFactory"],
    )
    public fun vertx(): Vertx = Vertx.vertx(buildVertxOptions(properties.vertx))

    @Bean
    @ConditionalOnMissingBean(value = [Mutiny.SessionFactory::class], name = ["hibernateSessionFactory"])
    public fun hibernateSessionFactory(vertx: Vertx): org.hibernate.SessionFactory {
        val resolvedJdbcUrl = jdbcUrl?.takeIf { it.isNotBlank() } ?: throw IllegalStateException(
            "Hibernate Reactive is on the classpath but 'spring.datasource.url' is not set. " +
                    "Set it, define your own Mutiny.SessionFactory bean, or exclude " +
                    "${javaClass.name} from auto-configuration.",
        )
        val reactiveUrl = ReactiveConnectionUrl.fromJdbc(resolvedJdbcUrl)

        val configuration =
            Configuration().apply {
                val basePackages = AutoConfigurationPackages.get(applicationContext)
                findManagedClasses(basePackages).forEach { managedClass ->
                    addManagedClass(this, managedClass)
                }
            }
                .setProperty(AvailableSettings.JAKARTA_JDBC_URL, reactiveUrl)
                .setProperty(AvailableSettings.HBM2DDL_AUTO, ddlAuto)
                .setProperty(AvailableSettings.SHOW_SQL, showSql.toString())
                .setProperty(AvailableSettings.FORMAT_SQL, formatSql.toString())
                .setProperty(AvailableSettings.USE_SQL_COMMENTS, useSqlComments.toString())
                .setProperty(AvailableSettings.HIGHLIGHT_SQL, highlightSql.toString())
                .setProperty(AvailableSettings.ORDER_INSERTS, orderInserts.toString())
                .setProperty(AvailableSettings.ORDER_UPDATES, orderUpdates.toString())
                .setProperty("hibernate.jdbc.batch_versioned_data", batchVersionedData.toString())
                .setProperty(AvailableSettings.FAIL_ON_PAGINATION_OVER_COLLECTION_FETCH, failOnPaginationOverCollectionFetch.toString())
                .setProperty(AvailableSettings.IN_CLAUSE_PARAMETER_PADDING, inClauseParameterPadding.toString())
                .setProperty(AvailableSettings.GLOBALLY_QUOTED_IDENTIFIERS, globallyQuotedIdentifiers.toString())
                .setProperty(AvailableSettings.GENERATE_STATISTICS, generateStatistics.toString())
                .setProperty(AvailableSettings.USE_SECOND_LEVEL_CACHE, useSecondLevelCache.toString())
                .setProperty(AvailableSettings.USE_QUERY_CACHE, useQueryCache.toString())
                .setProperty("hibernate.connection.pool_size", properties.poolSize.toString())

        // Credentials may be carried in the URL user info, so apply explicit values only when set.
        username?.let { configuration.setProperty(AvailableSettings.JAKARTA_JDBC_USER, it) }
        password?.let { configuration.setProperty(AvailableSettings.JAKARTA_JDBC_PASSWORD, it) }

        // Without an explicit dialect, Hibernate infers one from connection metadata.
        dialect?.takeIf { it.isNotBlank() }?.let {
            configuration.setProperty(AvailableSettings.DIALECT, it)
        }

        defaultBatchFetchSize?.let {
            configuration.setProperty(AvailableSettings.DEFAULT_BATCH_FETCH_SIZE, it.toString())
        }
        maxFetchDepth?.let {
            configuration.setProperty(AvailableSettings.MAX_FETCH_DEPTH, it.toString())
        }

        jdbcBatchSize?.let {
            configuration.setProperty(AvailableSettings.STATEMENT_BATCH_SIZE, it.toString())
        }
        jdbcFetchSize?.let {
            configuration.setProperty(AvailableSettings.STATEMENT_FETCH_SIZE, it.toString())
        }

        queryPlanCacheMaxSize?.let {
            configuration.setProperty(AvailableSettings.QUERY_PLAN_CACHE_MAX_SIZE, it.toString())
        }

        physicalNamingStrategy?.let {
            configuration.setProperty(AvailableSettings.PHYSICAL_NAMING_STRATEGY, it)
        }
        implicitNamingStrategy?.let {
            configuration.setProperty(AvailableSettings.IMPLICIT_NAMING_STRATEGY, it)
        }

        jdbcTimeZone?.let {
            configuration.setProperty(AvailableSettings.JDBC_TIME_ZONE, it)
        }

        properties.connectTimeout?.let {
            configuration.setProperty("hibernate.vertx.pool.connect_timeout", it.toString())
        }
        properties.idleTimeout?.let {
            configuration.setProperty("hibernate.vertx.pool.idle_timeout", it.toString())
        }
        properties.maxWaitQueueSize?.let {
            configuration.setProperty("hibernate.vertx.pool.max_wait_queue_size", it.toString())
        }

        extractUrlParameter(resolvedJdbcUrl, "currentSchema")?.let {
            configuration.setProperty(AvailableSettings.DEFAULT_SCHEMA, it)
        }

        // Explicit JPA properties override the defaults calculated above.
        passthroughJpaProperties().forEach { (key, value) ->
            configuration.setProperty(key, value)
        }

        val sslMode = resolveSslMode(resolvedJdbcUrl)
        if (sslMode != null && sslMode != "disable") {
            configuration.setProperty(
                "hibernate.vertx.pool.configuration_class",
                SslAwareSqlClientPoolConfiguration::class.java.name
            )
            configuration.setProperty("hibernate.vertx.pool.ssl.mode", sslMode)
            applicationContext.environment
                .getProperty("spring.jpa.properties.hibernate.reactive.trust-certificate")
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    configuration.setProperty("hibernate.vertx.pool.ssl.trust-certificate", it)
                }
        }

        val serviceRegistry =
            ReactiveServiceRegistryBuilder()
                .applySettings(configuration.properties)
                // Prevent Hibernate Reactive from creating a second Vert.x instance.
                .addService(VertxInstance::class.java, ProvidedVertxInstance(vertx))
                .build()

        return configuration.buildSessionFactory(serviceRegistry)
    }

    /**
     * Resolves the SSL mode.
     *
     * A non-`disable` property value takes precedence over the JDBC URL's `sslmode` parameter.
     *
     * @return the SSL mode, or `null` when unspecified
     */
    private fun resolveSslMode(jdbcUrl: String): String? {
        if (properties.sslMode != "disable") {
            return properties.sslMode
        }

        return extractSslModeFromUrl(jdbcUrl)
    }

    /**
     * Returns Hibernate settings declared under `spring.jpa.properties.*`.
     *
     * `hibernate.reactive.*` is excluded because it is consumed by this starter rather than
     * Hibernate.
     */
    private fun passthroughJpaProperties(): Map<String, String> =
        Binder.get(applicationContext.environment)
            .bind(JPA_PROPERTIES_PREFIX, Bindable.mapOf(String::class.java, String::class.java))
            .orElseGet(::emptyMap)
            .filterKeys { !it.startsWith(REACTIVE_PROPERTIES_PREFIX) }

    /**
     * Extracts the `sslmode` parameter from a JDBC URL.
     *
     * For example, `jdbc:postgresql://host:5432/db?sslmode=require` yields `require`.
     */
    private fun extractSslModeFromUrl(url: String): String? {
        return extractUrlParameter(url, "sslmode")
    }

    /**
     * Extracts a query parameter from a JDBC URL.
     *
     * For example, `jdbc:postgresql://host:5432/db?currentSchema=myschema` yields `myschema`.
     *
     * @param url JDBC URL
     * @param paramName parameter name
     * @return the parameter value, or `null`
     */
    private fun extractUrlParameter(url: String, paramName: String): String? {
        val regex = Regex("[?&]$paramName=([^&]+)")
        return regex.find(url)?.groupValues?.get(1)
    }

    @Bean
    @ConditionalOnMissingBean
    public fun reactiveSessionFactory(hibernateSessionFactory: org.hibernate.SessionFactory): Mutiny.SessionFactory =
        hibernateSessionFactory.unwrap(Mutiny.SessionFactory::class.java)

    @Bean
    @ConditionalOnMissingBean
    public fun reactiveSessionProvider(sessionFactory: Mutiny.SessionFactory): ReactiveSessionProvider =
        ReactiveSessionProvider(sessionFactory)

    @Bean
    @ConditionalOnMissingBean
    public fun ambientTransactionProbe(sessionFactory: Mutiny.SessionFactory): AmbientTransactionProbe =
        SpringAmbientTransactionProbe(sessionFactory)

    @Bean
    @ConditionalOnMissingBean
    public fun reactiveTransactionExecutor(
        sessionFactory: Mutiny.SessionFactory,
        ambientTransactionProbe: AmbientTransactionProbe,
    ): ReactiveTransactionExecutor =
        ReactiveTransactionExecutor(sessionFactory, ambientTransactionProbe)

    /**
     * Backs off when another [ReactiveTransactionManager] is registered.
     *
     * Checking the interface prevents multiple managers, such as an R2DBC manager and this one,
     * from causing `NoUniqueBeanDefinitionException` on the first `@Transactional` call.
     */
    @Bean
    @ConditionalOnMissingBean(ReactiveTransactionManager::class)
    public fun hibernateReactiveTransactionManager(
        reactiveSessionFactory: Mutiny.SessionFactory,
    ): HibernateReactiveTransactionManager =
        HibernateReactiveTransactionManager(reactiveSessionFactory)

    @Bean
    @ConditionalOnMissingBean
    public fun transactionalEventPublisher(
        applicationEventPublisher: ApplicationEventPublisher,
    ): TransactionalEventPublisher =
        TransactionalEventPublisher(applicationEventPublisher)

    @Bean
    @ConditionalOnMissingBean
    public fun transactionalAwareSessionProvider(
        sessionFactory: Mutiny.SessionFactory,
    ): TransactionalAwareSessionProvider =
        TransactionalAwareSessionProvider(sessionFactory)

    /**
     * Finds classes to register with the persistence unit.
     *
     * Scanning `@Embeddable`, `@MappedSuperclass`, and `@Converter` alongside `@Entity` matches
     * Spring Boot's blocking JPA mapping behavior. `@MappedSuperclass` is commonly abstract, so
     * the default candidate test is relaxed.
     */
    private fun findManagedClasses(basePackages: List<String>): List<Class<*>> {
        val scanner =
            object : ClassPathScanningCandidateComponentProvider(false) {
                override fun isCandidateComponent(beanDefinition: AnnotatedBeanDefinition): Boolean =
                    beanDefinition.metadata.isIndependent
            }.apply {
                setResourceLoader(applicationContext)
                addIncludeFilter(AnnotationTypeFilter(Entity::class.java))
                addIncludeFilter(AnnotationTypeFilter(Embeddable::class.java))
                addIncludeFilter(AnnotationTypeFilter(MappedSuperclass::class.java))
                addIncludeFilter(AnnotationTypeFilter(Converter::class.java))
            }

        val classLoader = applicationContext.classLoader ?: javaClass.classLoader

        return basePackages
            .flatMap { basePackage -> scanner.findCandidateComponents(basePackage) }
            .mapNotNull { it.beanClassName }
            .distinct()
            .map { ClassUtils.forName(it, classLoader) }
    }

    /**
     * Registers a scanned class with Hibernate [Configuration].
     *
     * `@Converter` requires its dedicated API for `autoApply` to take effect.
     */
    @Suppress("UNCHECKED_CAST")
    private fun addManagedClass(configuration: Configuration, managedClass: Class<*>) {
        if (managedClass.isAnnotationPresent(Converter::class.java) &&
            AttributeConverter::class.java.isAssignableFrom(managedClass)
        ) {
            configuration.addAttributeConverter(managedClass as Class<out AttributeConverter<*, *>>)
        } else {
            configuration.addAnnotatedClass(managedClass)
        }
    }

    private companion object {
        private const val JPA_PROPERTIES_PREFIX = "spring.jpa.properties"
        private const val REACTIVE_PROPERTIES_PREFIX = "hibernate.reactive."
    }
}

/**
 * Converts [HibernateReactiveProperties.VertxSettings] to [VertxOptions].
 *
 * Unset values retain Vert.x defaults. Durations are applied in nanoseconds to preserve
 * sub-millisecond values.
 */
internal fun buildVertxOptions(settings: HibernateReactiveProperties.VertxSettings): VertxOptions {
    val options = VertxOptions()
    settings.eventLoopPoolSize?.let { options.eventLoopPoolSize = it }
    settings.maxEventLoopExecuteTime?.let {
        options.maxEventLoopExecuteTime = it.toNanos()
        options.maxEventLoopExecuteTimeUnit = java.util.concurrent.TimeUnit.NANOSECONDS
    }
    settings.blockedThreadCheckInterval?.let {
        options.blockedThreadCheckInterval = it.toNanos()
        options.blockedThreadCheckIntervalUnit = java.util.concurrent.TimeUnit.NANOSECONDS
    }
    settings.warningExceptionTime?.let {
        options.warningExceptionTime = it.toNanos()
        options.warningExceptionTimeUnit = java.util.concurrent.TimeUnit.NANOSECONDS
    }
    return options
}
