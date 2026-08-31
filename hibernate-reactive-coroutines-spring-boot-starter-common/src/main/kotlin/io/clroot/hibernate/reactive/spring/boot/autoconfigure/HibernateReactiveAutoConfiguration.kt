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
 * 다음 Bean들을 자동으로 등록합니다:
 * - [Mutiny.SessionFactory]: Hibernate Reactive 세션 팩토리
 * - [ReactiveSessionProvider]: Adapter에서 사용하는 세션 헬퍼
 * - [ReactiveTransactionExecutor]: Service에서 사용하는 트랜잭션 래퍼
 * - [TransactionalEventPublisher]: reactive 트랜잭션 이벤트 발행기
 *
 * @Entity 클래스는 @SpringBootApplication 패키지 기준으로 자동 스캔됩니다.
 *
 * 기존 Spring 프로퍼티를 그대로 사용합니다:
 * - `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password`
 * - `spring.jpa.database-platform`, `spring.jpa.hibernate.ddl-auto`
 * - `spring.jpa.show-sql`, `spring.jpa.properties.hibernate.format_sql`
 *
 * Hibernate Reactive 전용 프로퍼티:
 * - `spring.jpa.properties.hibernate.reactive.pool-size`: 커넥션 풀 사이즈 (기본값: 10)
 * - `spring.jpa.properties.hibernate.reactive.ssl-mode`: SSL 모드 (기본값: disable)
 * - `spring.jpa.properties.hibernate.reactive.trust-certificate`: PEM CA 인증서 경로
 * - `spring.jpa.properties.hibernate.reactive.connect-timeout`: 커넥션 요청 타임아웃 (밀리초)
 * - `spring.jpa.properties.hibernate.reactive.idle-timeout`: 유휴 커넥션 타임아웃 (밀리초)
 * - `spring.jpa.properties.hibernate.reactive.max-wait-queue-size`: 대기 큐 최대 크기
 * - `spring.jpa.properties.hibernate.reactive.vertx.*`: 스타터가 생성하는 Vert.x 인스턴스 설정
 *   (이벤트 루프 수, blocked-thread checker 임계값)
 *
 * Vert.x 인스턴스는 스타터가 직접 생성해 [Vertx] 빈으로 노출하고 Hibernate Reactive에
 * [VertxInstance] 서비스로 주입합니다. 애플리케이션에 [Vertx] 빈이 이미 있으면 그 빈을
 * 재사용하므로, 앱 전체에서 Vert.x 인스턴스를 한 벌로 유지할 수 있습니다.
 *
 * SSL 모드는 다음 우선순위로 적용됩니다:
 * 1. `spring.jpa.properties.hibernate.reactive.ssl-mode` 프로퍼티 (disable이 아닌 경우)
 * 2. JDBC URL의 `sslmode` 파라미터 (예: `?sslmode=require`)
 */
@AutoConfiguration
@ConditionalOnClass(Mutiny.SessionFactory::class)
@EnableConfigurationProperties(HibernateReactiveProperties::class)
public class HibernateReactiveAutoConfiguration(
    private val applicationContext: ApplicationContext,
    private val properties: HibernateReactiveProperties,
    // === 데이터소스 설정 ===
    @Value("\${spring.datasource.url:#{null}}") private val jdbcUrl: String?,
    @Value("\${spring.datasource.username:#{null}}") private val username: String?,
    @Value("\${spring.datasource.password:#{null}}") private val password: String?,
    // === JPA 기본 설정 ===
    @Value("\${spring.jpa.database-platform:#{null}}") private val dialect: String?,
    @Value("\${spring.jpa.hibernate.ddl-auto:none}") private val ddlAuto: String,
    @Value("\${spring.jpa.show-sql:false}") private val showSql: Boolean,
    // === SQL 포맷팅 ===
    @Value("\${spring.jpa.properties.hibernate.format_sql:false}") private val formatSql: Boolean,
    @Value("\${spring.jpa.properties.hibernate.use_sql_comments:false}") private val useSqlComments: Boolean,
    @Value("\${spring.jpa.properties.hibernate.highlight_sql:false}") private val highlightSql: Boolean,
    // === Fetch 설정 ===
    @Value("\${spring.jpa.properties.hibernate.default_batch_fetch_size:#{null}}") private val defaultBatchFetchSize: Int?,
    @Value("\${spring.jpa.properties.hibernate.max_fetch_depth:#{null}}") private val maxFetchDepth: Int?,
    // === JDBC 배치 설정 ===
    @Value("\${spring.jpa.properties.hibernate.jdbc.batch_size:#{null}}") private val jdbcBatchSize: Int?,
    @Value("\${spring.jpa.properties.hibernate.jdbc.fetch_size:#{null}}") private val jdbcFetchSize: Int?,
    @Value("\${spring.jpa.properties.hibernate.order_inserts:false}") private val orderInserts: Boolean,
    @Value("\${spring.jpa.properties.hibernate.order_updates:false}") private val orderUpdates: Boolean,
    @Value("\${spring.jpa.properties.hibernate.jdbc.batch_versioned_data:true}") private val batchVersionedData: Boolean,
    // === 쿼리 설정 ===
    @Value("\${spring.jpa.properties.hibernate.query.plan_cache_max_size:#{null}}") private val queryPlanCacheMaxSize: Int?,
    @Value("\${spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch:false}") private val failOnPaginationOverCollectionFetch: Boolean,
    @Value("\${spring.jpa.properties.hibernate.query.in_clause_parameter_padding:false}") private val inClauseParameterPadding: Boolean,
    // === 식별자/네이밍 설정 ===
    @Value("\${spring.jpa.properties.hibernate.globally_quoted_identifiers:false}") private val globallyQuotedIdentifiers: Boolean,
    @Value("\${spring.jpa.properties.hibernate.physical_naming_strategy:#{null}}") private val physicalNamingStrategy: String?,
    @Value("\${spring.jpa.properties.hibernate.implicit_naming_strategy:#{null}}") private val implicitNamingStrategy: String?,
    // === JDBC 기타 설정 ===
    @Value("\${spring.jpa.properties.hibernate.jdbc.time_zone:#{null}}") private val jdbcTimeZone: String?,
    // === 통계/캐시 설정 ===
    @Value("\${spring.jpa.properties.hibernate.generate_statistics:false}") private val generateStatistics: Boolean,
    @Value("\${spring.jpa.properties.hibernate.cache.use_second_level_cache:false}") private val useSecondLevelCache: Boolean,
    @Value("\${spring.jpa.properties.hibernate.cache.use_query_cache:false}") private val useQueryCache: Boolean,
) {
    /**
     * Hibernate Reactive가 사용할 Vert.x 인스턴스.
     *
     * 애플리케이션에 [Vertx] 빈이 이미 있으면 물러나고 그 빈을 재사용합니다.
     * 스타터가 생성한 인스턴스는 컨텍스트 종료 시 `close()`로 정리됩니다
     * (세션 팩토리가 이 빈에 의존하므로 세션 팩토리가 먼저 닫힙니다).
     */
    @Bean
    @ConditionalOnMissingBean(Vertx::class)
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
                // @SpringBootApplication 패키지 기준으로 @Entity 클래스 자동 스캔
                val basePackages = AutoConfigurationPackages.get(applicationContext)
                findManagedClasses(basePackages).forEach { managedClass ->
                    addManagedClass(this, managedClass)
                }
            }
                // === 기본 연결 설정 ===
                .setProperty(AvailableSettings.JAKARTA_JDBC_URL, reactiveUrl)
                .setProperty(AvailableSettings.HBM2DDL_AUTO, ddlAuto)
                // === SQL 로깅 설정 ===
                .setProperty(AvailableSettings.SHOW_SQL, showSql.toString())
                .setProperty(AvailableSettings.FORMAT_SQL, formatSql.toString())
                .setProperty(AvailableSettings.USE_SQL_COMMENTS, useSqlComments.toString())
                .setProperty(AvailableSettings.HIGHLIGHT_SQL, highlightSql.toString())
                // === 배치/순서 설정 ===
                .setProperty(AvailableSettings.ORDER_INSERTS, orderInserts.toString())
                .setProperty(AvailableSettings.ORDER_UPDATES, orderUpdates.toString())
                .setProperty("hibernate.jdbc.batch_versioned_data", batchVersionedData.toString())
                // === 쿼리 설정 ===
                .setProperty(AvailableSettings.FAIL_ON_PAGINATION_OVER_COLLECTION_FETCH, failOnPaginationOverCollectionFetch.toString())
                .setProperty(AvailableSettings.IN_CLAUSE_PARAMETER_PADDING, inClauseParameterPadding.toString())
                // === 식별자 설정 ===
                .setProperty(AvailableSettings.GLOBALLY_QUOTED_IDENTIFIERS, globallyQuotedIdentifiers.toString())
                // === 통계/캐시 설정 ===
                .setProperty(AvailableSettings.GENERATE_STATISTICS, generateStatistics.toString())
                .setProperty(AvailableSettings.USE_SECOND_LEVEL_CACHE, useSecondLevelCache.toString())
                .setProperty(AvailableSettings.USE_QUERY_CACHE, useQueryCache.toString())
                // === 커넥션 풀 설정 ===
                .setProperty("hibernate.connection.pool_size", properties.poolSize.toString())

        // === Optional Hibernate 설정 (null 가능) ===

        // 자격 증명은 URL의 userinfo로도 전달될 수 있으므로 설정된 경우에만 적용합니다.
        username?.let { configuration.setProperty(AvailableSettings.JAKARTA_JDBC_USER, it) }
        password?.let { configuration.setProperty(AvailableSettings.JAKARTA_JDBC_PASSWORD, it) }

        // Dialect가 없으면 Hibernate가 커넥션 메타데이터로 자동 판별합니다.
        dialect?.takeIf { it.isNotBlank() }?.let {
            configuration.setProperty(AvailableSettings.DIALECT, it)
        }

        // Fetch 설정
        defaultBatchFetchSize?.let {
            configuration.setProperty(AvailableSettings.DEFAULT_BATCH_FETCH_SIZE, it.toString())
        }
        maxFetchDepth?.let {
            configuration.setProperty(AvailableSettings.MAX_FETCH_DEPTH, it.toString())
        }

        // JDBC 배치 설정
        jdbcBatchSize?.let {
            configuration.setProperty(AvailableSettings.STATEMENT_BATCH_SIZE, it.toString())
        }
        jdbcFetchSize?.let {
            configuration.setProperty(AvailableSettings.STATEMENT_FETCH_SIZE, it.toString())
        }

        // 쿼리 캐시 설정
        queryPlanCacheMaxSize?.let {
            configuration.setProperty(AvailableSettings.QUERY_PLAN_CACHE_MAX_SIZE, it.toString())
        }

        // 네이밍 전략
        physicalNamingStrategy?.let {
            configuration.setProperty(AvailableSettings.PHYSICAL_NAMING_STRATEGY, it)
        }
        implicitNamingStrategy?.let {
            configuration.setProperty(AvailableSettings.IMPLICIT_NAMING_STRATEGY, it)
        }

        // JDBC 타임존
        jdbcTimeZone?.let {
            configuration.setProperty(AvailableSettings.JDBC_TIME_ZONE, it)
        }

        // Vert.x 풀 타임아웃 설정
        properties.connectTimeout?.let {
            configuration.setProperty("hibernate.vertx.pool.connect_timeout", it.toString())
        }
        properties.idleTimeout?.let {
            configuration.setProperty("hibernate.vertx.pool.idle_timeout", it.toString())
        }
        properties.maxWaitQueueSize?.let {
            configuration.setProperty("hibernate.vertx.pool.max_wait_queue_size", it.toString())
        }

        // JDBC URL 쿼리 파라미터에서 Hibernate 설정 추출
        extractUrlParameter(resolvedJdbcUrl, "currentSchema")?.let {
            configuration.setProperty(AvailableSettings.DEFAULT_SCHEMA, it)
        }

        // 명시적으로 선언된 spring.jpa.properties.* 를 그대로 전달합니다.
        // 위에서 계산한 기본값보다 사용자의 명시적 설정이 우선합니다.
        passthroughJpaProperties().forEach { (key, value) ->
            configuration.setProperty(key, value)
        }

        // SSL 설정: 프로퍼티 우선, 없으면 URL 파라미터에서 추출
        val sslMode = resolveSslMode(resolvedJdbcUrl)
        if (sslMode != null && sslMode != "disable") {
            // 커스텀 SqlClientPoolConfiguration 등록
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
                // 스타터(또는 애플리케이션)가 소유한 Vert.x를 주입해, Hibernate Reactive 내부의
                // DefaultVertxInstance가 별도 Vert.x를 하나 더 띄우지 않게 한다.
                .addService(VertxInstance::class.java, ProvidedVertxInstance(vertx))
                .build()

        return configuration.buildSessionFactory(serviceRegistry)
    }

    /**
     * SSL 모드를 결정합니다.
     *
     * 우선순위:
     * 1. `spring.jpa.properties.hibernate.reactive.ssl-mode` 프로퍼티 (disable이 아닌 경우)
     * 2. JDBC URL의 `sslmode` 파라미터
     *
     * @return SSL 모드 문자열 또는 null
     */
    private fun resolveSslMode(jdbcUrl: String): String? {
        // 1. 프로퍼티에서 명시적으로 설정된 경우 우선
        if (properties.sslMode != "disable") {
            return properties.sslMode
        }

        // 2. URL 파라미터에서 sslmode 추출
        return extractSslModeFromUrl(jdbcUrl)
    }

    /**
     * `spring.jpa.properties.*` 로 선언된 모든 Hibernate 설정을 반환합니다.
     *
     * 이 스타터가 명시적으로 처리하는 `hibernate.reactive.*` 는 Hibernate가 알지 못하는
     * 키이므로 제외합니다.
     */
    private fun passthroughJpaProperties(): Map<String, String> =
        Binder.get(applicationContext.environment)
            .bind(JPA_PROPERTIES_PREFIX, Bindable.mapOf(String::class.java, String::class.java))
            .orElseGet(::emptyMap)
            .filterKeys { !it.startsWith(REACTIVE_PROPERTIES_PREFIX) }

    /**
     * JDBC URL에서 sslmode 파라미터를 추출합니다.
     *
     * 예: `jdbc:postgresql://host:5432/db?sslmode=require` -> `require`
     */
    private fun extractSslModeFromUrl(url: String): String? {
        return extractUrlParameter(url, "sslmode")
    }

    /**
     * JDBC URL에서 지정된 쿼리 파라미터 값을 추출합니다.
     *
     * 예: `jdbc:postgresql://host:5432/db?currentSchema=myschema` -> `myschema`
     *
     * @param url JDBC URL
     * @param paramName 추출할 파라미터 이름
     * @return 파라미터 값 또는 null
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
     * 다른 [ReactiveTransactionManager]가 이미 등록되어 있으면 물러납니다.
     *
     * 타입이 아닌 구현 클래스만 검사하면 R2DBC 등과 공존할 때 트랜잭션 매니저가 둘이 되어
     * 첫 `@Transactional` 호출 시점에 `NoUniqueBeanDefinitionException`이 발생합니다.
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
     * 영속성 유닛에 등록할 클래스들을 스캔합니다.
     *
     * `@Entity` 뿐 아니라 `@Embeddable`, `@MappedSuperclass`, `@Converter` 까지 등록해야
     * Spring Boot의 블로킹 JPA와 동일한 매핑 결과를 얻을 수 있습니다.
     * `@MappedSuperclass`는 보통 추상 클래스이므로 기본 후보 판별을 완화합니다.
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
     * 스캔된 클래스를 Hibernate [Configuration]에 등록합니다.
     *
     * `@Converter`는 전용 API를 사용해야 `autoApply` 속성이 반영됩니다.
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
 * [HibernateReactiveProperties.VertxSettings]를 [VertxOptions]로 변환합니다.
 *
 * 설정하지 않은 값은 Vert.x 기본값을 그대로 둡니다.
 * 시간 값은 나노초 단위로 적용해 밀리초 미만 값도 유실되지 않습니다.
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
