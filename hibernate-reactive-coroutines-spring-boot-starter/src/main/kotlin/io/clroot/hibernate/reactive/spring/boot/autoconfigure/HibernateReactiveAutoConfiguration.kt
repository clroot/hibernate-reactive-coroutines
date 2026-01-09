package io.clroot.hibernate.reactive.spring.boot.autoconfigure

import io.clroot.hibernate.reactive.ReactiveSessionProvider
import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
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
 */
@AutoConfiguration
@ConditionalOnClass(Mutiny.SessionFactory::class)
@EnableConfigurationProperties(HibernateReactiveProperties::class)
class HibernateReactiveAutoConfiguration(
    private val applicationContext: ApplicationContext,
    private val properties: HibernateReactiveProperties,
    @Value("\${spring.datasource.url}") private val jdbcUrl: String,
    @Value("\${spring.datasource.username}") private val username: String,
    @Value("\${spring.datasource.password}") private val password: String,
    @Value("\${spring.jpa.database-platform}") private val dialect: String,
    @Value("\${spring.jpa.hibernate.ddl-auto:none}") private val ddlAuto: String,
    @Value("\${spring.jpa.show-sql:false}") private val showSql: Boolean,
    @Value("\${spring.jpa.properties.hibernate.format_sql:false}") private val formatSql: Boolean,
) {
    @Bean
    @ConditionalOnMissingBean
    fun reactiveSessionFactory(): Mutiny.SessionFactory {
        val reactiveUrl = jdbcUrl.replace("jdbc:", "")

        val configuration =
            Configuration().apply {
                // @SpringBootApplication 패키지 기준으로 @Entity 클래스 자동 스캔
                val basePackages = AutoConfigurationPackages.get(applicationContext)
                findEntityClasses(basePackages).forEach { entityClass ->
                    addAnnotatedClass(entityClass)
                }
            }
                .setProperty(AvailableSettings.JAKARTA_JDBC_URL, reactiveUrl)
                .setProperty(AvailableSettings.JAKARTA_JDBC_USER, username)
                .setProperty(AvailableSettings.JAKARTA_JDBC_PASSWORD, password)
                .setProperty(AvailableSettings.DIALECT, dialect)
                .setProperty(AvailableSettings.HBM2DDL_AUTO, ddlAuto)
                .setProperty(AvailableSettings.SHOW_SQL, showSql.toString())
                .setProperty(AvailableSettings.FORMAT_SQL, formatSql.toString())
                .setProperty("hibernate.connection.pool_size", properties.poolSize.toString())

        val serviceRegistry =
            ReactiveServiceRegistryBuilder()
                .applySettings(configuration.properties)
                .build()

        return configuration
            .buildSessionFactory(serviceRegistry)
            .unwrap(Mutiny.SessionFactory::class.java)
    }

    @Bean
    @ConditionalOnMissingBean
    fun reactiveSessionProvider(sessionFactory: Mutiny.SessionFactory): ReactiveSessionProvider =
        ReactiveSessionProvider(sessionFactory)

    @Bean
    @ConditionalOnMissingBean
    fun reactiveTransactionExecutor(sessionFactory: Mutiny.SessionFactory): ReactiveTransactionExecutor =
        ReactiveTransactionExecutor(sessionFactory)

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
