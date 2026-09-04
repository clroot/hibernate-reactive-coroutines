package io.clroot.hibernate.reactive.spring.boot.pool

import io.vertx.core.net.ClientSSLOptions
import io.vertx.core.net.PemTrustOptions
import io.vertx.sqlclient.SqlConnectOptions
import org.hibernate.internal.util.config.ConfigurationHelper
import org.hibernate.reactive.pool.impl.DefaultSqlClientPoolConfiguration
import org.slf4j.LoggerFactory
import java.net.URI

private const val PG_CONNECT_OPTIONS_CLASS = "io.vertx.pgclient.PgConnectOptions"

/**
 * Copies every [SqlConnectOptions] property through Vert.x's native PgConnectOptions copy constructor.
 * Reflection keeps `vertx-pg-client` an optional runtime dependency.
 */
internal fun copyPostgresConnectOptions(source: SqlConnectOptions): SqlConnectOptions {
    val pgConnectOptionsClass = Class.forName(PG_CONNECT_OPTIONS_CLASS)
    return pgConnectOptionsClass
        .getConstructor(SqlConnectOptions::class.java)
        .newInstance(source) as SqlConnectOptions
}

/** Applies PostgreSQL SSL modes while keeping `vertx-pg-client` optional through reflection. */
public class SslAwareSqlClientPoolConfiguration : DefaultSqlClientPoolConfiguration() {

    public companion object {
        private const val SSL_MODE_PROPERTY = "hibernate.vertx.pool.ssl.mode"
        private const val TRUST_CERTIFICATE_PROPERTY = "hibernate.vertx.pool.ssl.trust-certificate"
        private const val SSL_MODE_CLASS = "io.vertx.pgclient.SslMode"

        private val logger = LoggerFactory.getLogger(SslAwareSqlClientPoolConfiguration::class.java)

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

    private fun isPostgresUri(uri: URI): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        return scheme == "postgresql" || scheme == "postgres"
    }

    /** Applies SSL to PostgreSQL options without requiring `vertx-pg-client` at compile time. */
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

            // Vert.x's copy constructor preserves current and future SqlConnectOptions properties.
            val pgOptions = copyPostgresConnectOptions(baseOptions)

            val sslModeOfMethod = sslModeClass.getMethod("of", String::class.java)
            val sslModeValue = sslModeOfMethod.invoke(null, configuredSslMode)
            val setSslModeMethod = pgConnectOptionsClass.getMethod("setSslMode", sslModeClass)
            setSslModeMethod.invoke(pgOptions, sslModeValue)

            // Vert.x 5 moved TLS settings to ClientSSLOptions.
            applySslOptions(pgOptions, pgConnectOptionsClass, configuredSslMode)

            logger.info(
                "Created PgConnectOptions with verified SSL mode '{}' for PostgreSQL connection",
                configuredSslMode,
            )

            return pgOptions
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to configure PostgreSQL SSL mode '$configuredSslMode'; " +
                    "refusing to continue without the requested TLS settings",
                e,
            )
        }
    }

    /**
     * Applies TLS settings through [ClientSSLOptions].
     *
     * Vert.x 5 removed `setTrustAll`, `setPemTrustOptions`, and
     * `setHostnameVerificationAlgorithm` from PgConnectOptions in favor of
     * `setSslOptions(ClientSSLOptions)`.
     */
    private fun applySslOptions(
        pgOptions: Any,
        pgConnectOptionsClass: Class<*>,
        configuredSslMode: String,
    ) {
        val sslOptions = ClientSSLOptions()
            // Never bypass certificate verification.
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
}
