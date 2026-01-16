package io.clroot.hibernate.reactive.spring.boot.autoconfigure

import io.clroot.hibernate.reactive.ReactiveSessionProvider
import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.spring.boot.pool.SslAwareSqlClientPoolConfiguration
import io.clroot.hibernate.reactive.spring.boot.transaction.HibernateReactiveTransactionManager
import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import jakarta.persistence.Entity
import org.hibernate.cfg.AvailableSettings
import org.hibernate.cfg.Configuration
import org.hibernate.reactive.mutiny.Mutiny
import org.hibernate.reactive.provider.ReactiveServiceRegistryBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationPackages
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.transaction.annotation.EnableTransactionManagement

/**
 * Hibernate Reactive Auto-configuration.
 *
 * 다음 Bean들을 자동으로 등록합니다:
 * - [Mutiny.SessionFactory]: Hibernate Reactive 세션 팩토리
 * - [ReactiveSessionProvider]: Adapter에서 사용하는 세션 헬퍼
 * - [ReactiveTransactionExecutor]: Service에서 사용하는 트랜잭션 래퍼
 *
 * @Entity 클래스는 @SpringBootApplication 패키지 기준으로 자동 스캔됩니다.
 *
 * 기존 Spring 프로퍼티를 그대로 사용합니다:
 * - `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password`
 * - `spring.jpa.database-platform`, `spring.jpa.hibernate.ddl-auto`
 * - `spring.jpa.show-sql`, `spring.jpa.properties.hibernate.format_sql`
 *
 * Hibernate Reactive 전용 프로퍼티:
 * - `kotlin.hibernate.reactive.pool-size`: 커넥션 풀 사이즈 (기본값: 10)
 * - `kotlin.hibernate.reactive.ssl-mode`: SSL 모드 (기본값: disable)
 * - `kotlin.hibernate.reactive.connect-timeout`: 커넥션 요청 타임아웃 (밀리초)
 * - `kotlin.hibernate.reactive.idle-timeout`: 유휴 커넥션 타임아웃 (밀리초)
 * - `kotlin.hibernate.reactive.max-wait-queue-size`: 대기 큐 최대 크기
 *
 * SSL 모드는 다음 우선순위로 적용됩니다:
 * 1. `kotlin.hibernate.reactive.ssl-mode` 프로퍼티 (disable이 아닌 경우)
 * 2. JDBC URL의 `sslmode` 파라미터 (예: `?sslmode=require`)
 */
@AutoConfiguration
@ConditionalOnClass(Mutiny.SessionFactory::class)
@EnableConfigurationProperties(HibernateReactiveProperties::class)
@EnableTransactionManagement
class HibernateReactiveAutoConfiguration(
    private val applicationContext: ApplicationContext,
    private val properties: HibernateReactiveProperties,
    // === 데이터소스 설정 ===
    @Value("\${spring.datasource.url}") private val jdbcUrl: String,
    @Value("\${spring.datasource.username}") private val username: String,
    @Value("\${spring.datasource.password}") private val password: String,
    // === JPA 기본 설정 ===
    @Value("\${spring.jpa.database-platform}") private val dialect: String,
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
    @Bean
    @ConditionalOnMissingBean(name = ["hibernateSessionFactory"])
    fun hibernateSessionFactory(): org.hibernate.SessionFactory {
        val reactiveUrl = jdbcUrl.replace("jdbc:", "")

        val configuration =
            Configuration().apply {
                // @SpringBootApplication 패키지 기준으로 @Entity 클래스 자동 스캔
                val basePackages = AutoConfigurationPackages.get(applicationContext)
                findEntityClasses(basePackages).forEach { entityClass ->
                    addAnnotatedClass(entityClass)
                }
            }
                // === 기본 연결 설정 ===
                .setProperty(AvailableSettings.JAKARTA_JDBC_URL, reactiveUrl)
                .setProperty(AvailableSettings.JAKARTA_JDBC_USER, username)
                .setProperty(AvailableSettings.JAKARTA_JDBC_PASSWORD, password)
                .setProperty(AvailableSettings.DIALECT, dialect)
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
        extractUrlParameter(jdbcUrl, "currentSchema")?.let {
            configuration.setProperty(AvailableSettings.DEFAULT_SCHEMA, it)
        }

        // SSL 설정: 프로퍼티 우선, 없으면 URL 파라미터에서 추출
        val sslMode = resolveSslMode()
        if (sslMode != null && sslMode != "disable") {
            // 커스텀 SqlClientPoolConfiguration 등록
            configuration.setProperty(
                "hibernate.vertx.pool.configuration_class",
                SslAwareSqlClientPoolConfiguration::class.java.name
            )
            configuration.setProperty("hibernate.vertx.pool.ssl.mode", sslMode)
        }

        val serviceRegistry =
            ReactiveServiceRegistryBuilder()
                .applySettings(configuration.properties)
                .build()

        return configuration.buildSessionFactory(serviceRegistry)
    }

    /**
     * SSL 모드를 결정합니다.
     *
     * 우선순위:
     * 1. `kotlin.hibernate.reactive.ssl-mode` 프로퍼티 (disable이 아닌 경우)
     * 2. JDBC URL의 `sslmode` 파라미터
     *
     * @return SSL 모드 문자열 또는 null
     */
    private fun resolveSslMode(): String? {
        // 1. 프로퍼티에서 명시적으로 설정된 경우 우선
        if (properties.sslMode != "disable") {
            return properties.sslMode
        }

        // 2. URL 파라미터에서 sslmode 추출
        return extractSslModeFromUrl(jdbcUrl)
    }

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
    fun reactiveSessionFactory(hibernateSessionFactory: org.hibernate.SessionFactory): Mutiny.SessionFactory =
        hibernateSessionFactory.unwrap(Mutiny.SessionFactory::class.java)

    @Bean
    @ConditionalOnMissingBean
    fun reactiveSessionProvider(sessionFactory: Mutiny.SessionFactory): ReactiveSessionProvider =
        ReactiveSessionProvider(sessionFactory)

    @Bean
    @ConditionalOnMissingBean
    fun reactiveTransactionExecutor(sessionFactory: Mutiny.SessionFactory): ReactiveTransactionExecutor =
        ReactiveTransactionExecutor(sessionFactory)

    @Bean
    @ConditionalOnMissingBean
    fun hibernateReactiveTransactionManager(reactiveSessionFactory: Mutiny.SessionFactory): HibernateReactiveTransactionManager =
        HibernateReactiveTransactionManager(reactiveSessionFactory)

    @Bean
    @ConditionalOnMissingBean
    fun transactionalAwareSessionProvider(sessionFactory: Mutiny.SessionFactory): TransactionalAwareSessionProvider =
        TransactionalAwareSessionProvider(sessionFactory)

    private fun findEntityClasses(basePackages: List<String>): List<Class<*>> {
        val scanner =
            ClassPathScanningCandidateComponentProvider(false).apply {
                addIncludeFilter(AnnotationTypeFilter(Entity::class.java))
            }

        return basePackages.flatMap { basePackage ->
            scanner
                .findCandidateComponents(basePackage)
                .mapNotNull { it.beanClassName }
                .map { Class.forName(it) }
        }
    }
}
