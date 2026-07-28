package io.clroot.hibernate.reactive.spring.boot.pool

import io.vertx.core.net.ClientSSLOptions
import io.vertx.core.net.PemTrustOptions
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
public class SslAwareSqlClientPoolConfiguration : DefaultSqlClientPoolConfiguration() {

    public companion object {
        private const val SSL_MODE_PROPERTY = "hibernate.vertx.pool.ssl.mode"
        private const val TRUST_CERTIFICATE_PROPERTY = "hibernate.vertx.pool.ssl.trust-certificate"
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
                false
            }
        }
    }

    private var sslMode: String? = null
    private var trustCertificate: String? = null

    override fun configure(configuration: Map<*, *>) {
        super.configure(configuration)
        sslMode = ConfigurationHelper.getString(SSL_MODE_PROPERTY, configuration)?.trim()?.lowercase()
        trustCertificate = ConfigurationHelper.getString(TRUST_CERTIFICATE_PROPERTY, configuration)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        logger.info(
            "SslAwareSqlClientPoolConfiguration configured with sslMode: {}, trustCertificate: {}",
            sslMode,
            trustCertificate?.let { "configured" } ?: "default trust store",
        )
    }

    override fun connectOptions(uri: URI): SqlConnectOptions {
        val baseOptions = super.connectOptions(uri)

        // SSL 모드가 설정되지 않았거나 disable이면 기본 옵션 반환
        if (sslMode == null || sslMode == "disable") {
            return baseOptions
        }

        if (sslMode.isNullOrBlank()) {
            throw IllegalStateException("PostgreSQL SSL mode must not be blank")
        }

        if (!isPostgresUri(uri)) {
            throw IllegalStateException(
                "PostgreSQL SSL mode '$sslMode' was configured for unsupported database URI '$uri'",
            )
        }

        if (!pgClientAvailable) {
            throw IllegalStateException(
                "PostgreSQL SSL mode '$sslMode' requires vertx-pg-client on the runtime classpath",
            )
        }

        return createPgConnectOptionsWithSsl(baseOptions)
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
        val configuredSslMode = requireNotNull(sslMode)
        if (configuredSslMode in setOf("verify-ca", "verify-full") && trustCertificate == null) {
            throw IllegalStateException(
                "SSL mode '$configuredSslMode' requires " +
                    "spring.jpa.properties.hibernate.reactive.trust-certificate",
            )
        }

        try {
            val pgConnectOptionsClass = Class.forName(PG_CONNECT_OPTIONS_CLASS)
            val sslModeClass = Class.forName(SSL_MODE_CLASS)

            // PgConnectOptions 인스턴스 생성
            val pgOptions = pgConnectOptionsClass.getDeclaredConstructor().newInstance()

            // 기본 옵션 복사
            copyBaseOptions(baseOptions, pgOptions, pgConnectOptionsClass)

            // SSL 모드 설정
            val sslModeOfMethod = sslModeClass.getMethod("of", String::class.java)
            val sslModeValue = sslModeOfMethod.invoke(null, configuredSslMode)
            val setSslModeMethod = pgConnectOptionsClass.getMethod("setSslMode", sslModeClass)
            setSslModeMethod.invoke(pgOptions, sslModeValue)

            // Vert.x 5부터 TLS 설정은 ClientSSLOptions로 분리되었습니다.
            applySslOptions(pgOptions, pgConnectOptionsClass, configuredSslMode)

            logger.info(
                "Created PgConnectOptions with verified SSL mode '{}' for PostgreSQL connection",
                configuredSslMode,
            )

            return pgOptions as SqlConnectOptions
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to configure PostgreSQL SSL mode '$configuredSslMode'; " +
                    "refusing to continue without the requested TLS settings",
                e,
            )
        }
    }

    /**
     * TLS 설정을 [ClientSSLOptions]로 구성해 PgConnectOptions에 적용합니다.
     *
     * Vert.x 5에서 `setTrustAll`/`setPemTrustOptions`/`setHostnameVerificationAlgorithm`이
     * PgConnectOptions에서 제거되고 `setSslOptions(ClientSSLOptions)`로 통합되었습니다.
     */
    private fun applySslOptions(
        pgOptions: Any,
        pgConnectOptionsClass: Class<*>,
        configuredSslMode: String,
    ) {
        val sslOptions = ClientSSLOptions()
            // 어떤 SSL 모드에서도 인증서 검증을 우회하지 않음
            .setTrustAll(false)

        trustCertificate?.let { certificatePath ->
            sslOptions.trustOptions = PemTrustOptions().addCertPath(certificatePath)
        }

        if (configuredSslMode == "verify-full") {
            sslOptions.hostnameVerificationAlgorithm = "HTTPS"
        }

        pgConnectOptionsClass
            .getMethod("setSslOptions", ClientSSLOptions::class.java)
            .invoke(pgOptions, sslOptions)
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
        }

        // Properties 복사
        val properties = source.properties
        if (properties != null && properties.isNotEmpty()) {
            val setPropertiesMethod = targetClass.getMethod("setProperties", Map::class.java)
            setPropertiesMethod.invoke(target, properties)
        }
    }
}
