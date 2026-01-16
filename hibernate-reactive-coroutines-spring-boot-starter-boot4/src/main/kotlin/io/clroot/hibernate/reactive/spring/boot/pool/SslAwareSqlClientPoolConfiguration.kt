package io.clroot.hibernate.reactive.spring.boot.pool

import io.vertx.sqlclient.SqlConnectOptions
import org.hibernate.internal.util.config.ConfigurationHelper
import org.hibernate.reactive.pool.impl.DefaultSqlClientPoolConfiguration
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * SSL을 지원하는 SqlClientPoolConfiguration.
 *
 * PostgreSQL 연결 시 SSL 모드를 적용합니다.
 * `vertx-pg-client` 의존성이 없어도 동작하도록 Reflection을 사용합니다.
 */
class SslAwareSqlClientPoolConfiguration : DefaultSqlClientPoolConfiguration() {

    companion object {
        private const val SSL_MODE_PROPERTY = "hibernate.vertx.pool.ssl.mode"
        private const val PG_CONNECT_OPTIONS_CLASS = "io.vertx.pgclient.PgConnectOptions"
        private const val SSL_MODE_CLASS = "io.vertx.pgclient.SslMode"

        private val logger = LoggerFactory.getLogger(SslAwareSqlClientPoolConfiguration::class.java)

        /**
         * vertx-pg-client가 클래스패스에 있는지 확인
         */
        private val pgClientAvailable: Boolean by lazy {
            try {
                Class.forName(PG_CONNECT_OPTIONS_CLASS)
                true
            } catch (e: ClassNotFoundException) {
                logger.debug("vertx-pg-client not found in classpath, SSL mode configuration will be skipped")
                false
            }
        }
    }

    private var sslMode: String? = null

    override fun configure(configuration: MutableMap<Any?, Any?>?) {
        super.configure(configuration)
        sslMode = ConfigurationHelper.getString(SSL_MODE_PROPERTY, configuration)
        logger.info("SslAwareSqlClientPoolConfiguration configured with sslMode: {}", sslMode)
    }

    override fun connectOptions(uri: URI): SqlConnectOptions {
        val baseOptions = super.connectOptions(uri)

        // SSL 모드가 설정되지 않았거나 disable이면 기본 옵션 반환
        if (sslMode.isNullOrBlank() || sslMode == "disable") {
            return baseOptions
        }

        // PostgreSQL이고 vertx-pg-client가 있으면 PgConnectOptions로 변환
        if (isPostgresUri(uri) && pgClientAvailable) {
            return createPgConnectOptionsWithSsl(baseOptions)
        }

        return baseOptions
    }

    /**
     * URI가 PostgreSQL인지 확인합니다.
     */
    private fun isPostgresUri(uri: URI): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        return scheme == "postgresql" || scheme == "postgres"
    }

    /**
     * SqlConnectOptions를 PgConnectOptions로 변환하고 SSL 모드를 적용합니다.
     * Reflection을 사용하여 vertx-pg-client 의존성 없이 동작합니다.
     */
    private fun createPgConnectOptionsWithSsl(baseOptions: SqlConnectOptions): SqlConnectOptions {
        try {
            val pgConnectOptionsClass = Class.forName(PG_CONNECT_OPTIONS_CLASS)
            val sslModeClass = Class.forName(SSL_MODE_CLASS)

            // PgConnectOptions 인스턴스 생성
            val pgOptions = pgConnectOptionsClass.getDeclaredConstructor().newInstance()

            // 기본 옵션 복사
            copyBaseOptions(baseOptions, pgOptions, pgConnectOptionsClass)

            // SSL 모드 설정
            val sslModeOfMethod = sslModeClass.getMethod("of", String::class.java)
            val sslModeValue = sslModeOfMethod.invoke(null, sslMode)
            val setSslModeMethod = pgConnectOptionsClass.getMethod("setSslMode", sslModeClass)
            setSslModeMethod.invoke(pgOptions, sslModeValue)

            // require 모드에서는 인증서 검증 없이 암호화만 사용 (AWS RDS 등)
            // verify-ca, verify-full 모드에서는 trustAll을 false로 유지
            if (sslMode == "require" || sslMode == "prefer" || sslMode == "allow") {
                val setTrustAllMethod = pgConnectOptionsClass.getMethod("setTrustAll", Boolean::class.java)
                setTrustAllMethod.invoke(pgOptions, true)
                logger.debug("Set trustAll=true for SSL mode '{}'", sslMode)
            }

            logger.info("Created PgConnectOptions with SSL mode '{}' for PostgreSQL connection", sslMode)

            return pgOptions as SqlConnectOptions
        } catch (e: Exception) {
            logger.warn("Failed to create PgConnectOptions with SSL mode '{}': {}", sslMode, e.message)
            logger.debug("SSL mode configuration error details", e)
            return baseOptions
        }
    }

    /**
     * 기본 SqlConnectOptions의 설정을 PgConnectOptions로 복사합니다.
     */
    private fun copyBaseOptions(source: SqlConnectOptions, target: Any, targetClass: Class<*>) {
        // SqlConnectOptions의 공통 setter 메서드들
        val setters = listOf(
            "setHost" to { source.host },
            "setPort" to { source.port },
            "setDatabase" to { source.database },
            "setUser" to { source.user },
            "setPassword" to { source.password },
            "setCachePreparedStatements" to { source.cachePreparedStatements },
            "setPreparedStatementCacheMaxSize" to { source.preparedStatementCacheMaxSize },
        )

        for ((methodName, valueProvider) in setters) {
            try {
                val value = valueProvider()
                if (value != null) {
                    val paramType = when (value) {
                        is Int -> Int::class.java
                        is Boolean -> Boolean::class.java
                        else -> String::class.java
                    }
                    val method = targetClass.getMethod(methodName, paramType)
                    method.invoke(target, value)
                }
            } catch (e: Exception) {
                logger.trace("Could not copy option via {}: {}", methodName, e.message)
            }
        }

        // Properties 복사
        try {
            val properties = source.properties
            if (properties != null && properties.isNotEmpty()) {
                val setPropertiesMethod = targetClass.getMethod("setProperties", Map::class.java)
                setPropertiesMethod.invoke(target, properties)
            }
        } catch (e: Exception) {
            logger.trace("Could not copy properties: {}", e.message)
        }
    }
}
