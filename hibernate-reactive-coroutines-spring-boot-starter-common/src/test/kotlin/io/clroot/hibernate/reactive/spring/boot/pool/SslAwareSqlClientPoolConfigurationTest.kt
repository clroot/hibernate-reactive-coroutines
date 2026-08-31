package io.clroot.hibernate.reactive.spring.boot.pool

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.vertx.core.net.PemTrustOptions
import io.vertx.core.tracing.TracingPolicy
import io.vertx.pgclient.PgConnectOptions
import io.vertx.pgclient.SslMode
import io.vertx.sqlclient.SqlConnectOptions
import java.net.URI

class SslAwareSqlClientPoolConfigurationTest : FunSpec({

    test("invalid SSL mode fails closed") {
        val configuration = sslConfiguration("reqire")

        shouldThrow<IllegalStateException> {
            configuration.connectOptions(POSTGRES_URI)
        }
    }

    test("blank SSL mode fails closed") {
        val configuration = sslConfiguration("   ")

        shouldThrow<IllegalStateException> {
            configuration.connectOptions(POSTGRES_URI)
        }
    }

    test("require mode keeps certificate verification enabled") {
        val options = sslConfiguration("require").connectOptions(POSTGRES_URI) as PgConnectOptions

        options.sslMode shouldBe SslMode.REQUIRE
        options.sslOptions.isTrustAll shouldBe false
    }

    test("native copy constructor preserves every generic connect option") {
        val cacheFilter = { sql: String -> sql.startsWith("select") }
        val source = SqlConnectOptions()
            .setHost("database.internal")
            .setPort(6543)
            .setDatabase("application")
            .setUser("app-user")
            .setPassword("secret")
            .setCachePreparedStatements(true)
            .setPreparedStatementCacheMaxSize(512)
            .setPreparedStatementCacheSqlFilter(cacheFilter)
            .setProperties(mapOf("application_name" to "hibernate-reactive-coroutines"))
            .setTracingPolicy(TracingPolicy.ALWAYS)
            .setReconnectAttempts(7)
            .setReconnectInterval(1_500)
            .setMetricsName("postgres-primary")

        val copy = copyPostgresConnectOptions(source) as PgConnectOptions

        copy.host shouldBe source.host
        copy.port shouldBe source.port
        copy.database shouldBe source.database
        copy.user shouldBe source.user
        copy.password shouldBe source.password
        copy.cachePreparedStatements shouldBe source.cachePreparedStatements
        copy.preparedStatementCacheMaxSize shouldBe source.preparedStatementCacheMaxSize
        copy.preparedStatementCacheSqlFilter.test("select 1") shouldBe true
        copy.preparedStatementCacheSqlFilter.test("update example") shouldBe false
        copy.properties shouldBe source.properties
        copy.tracingPolicy shouldBe source.tracingPolicy
        copy.reconnectAttempts shouldBe source.reconnectAttempts
        copy.reconnectInterval shouldBe source.reconnectInterval
        copy.metricsName shouldBe source.metricsName
    }

    test("SSL conversion preserves Hibernate prepared statement SQL limit") {
        val options = sslConfiguration(
            mode = "require",
            additionalProperties = mapOf(PREPARED_STATEMENT_CACHE_SQL_LIMIT_PROPERTY to 8_192),
        ).connectOptions(POSTGRES_URI) as PgConnectOptions

        options.preparedStatementCacheSqlFilter.test("x".repeat(8_192)) shouldBe true
        options.preparedStatementCacheSqlFilter.test("x".repeat(8_193)) shouldBe false
    }

    test("verify-ca requires an explicit trust certificate") {
        val configuration = sslConfiguration("verify-ca")

        shouldThrow<IllegalStateException> {
            configuration.connectOptions(POSTGRES_URI)
        }
    }

    test("trust certificate is applied as PEM trust options") {
        val options = sslConfiguration(
            mode = "verify-ca",
            trustCertificate = "/run/secrets/postgres-ca.pem",
        ).connectOptions(POSTGRES_URI) as PgConnectOptions

        options.sslMode shouldBe SslMode.VERIFY_CA
        (options.sslOptions.trustOptions as PemTrustOptions).certPaths shouldContainExactly
            listOf("/run/secrets/postgres-ca.pem")
        options.sslOptions.isTrustAll shouldBe false
    }

    test("verify-full enables hostname verification") {
        val options = sslConfiguration(
            mode = "verify-full",
            trustCertificate = "/run/secrets/postgres-ca.pem",
        ).connectOptions(POSTGRES_URI) as PgConnectOptions

        options.sslMode shouldBe SslMode.VERIFY_FULL
        options.sslOptions.hostnameVerificationAlgorithm shouldBe "HTTPS"
    }
}) {
    companion object {
        private val POSTGRES_URI = URI("postgresql://localhost:5432/test")
        private const val SSL_MODE_PROPERTY = "hibernate.vertx.pool.ssl.mode"
        private const val TRUST_CERTIFICATE_PROPERTY = "hibernate.vertx.pool.ssl.trust-certificate"
        private const val PREPARED_STATEMENT_CACHE_SQL_LIMIT_PROPERTY =
            "hibernate.vertx.prepared_statement_cache.sql_limit"

        private fun sslConfiguration(
            mode: String,
            trustCertificate: String? = null,
            additionalProperties: Map<String, Any> = emptyMap(),
        ): SslAwareSqlClientPoolConfiguration {
            val properties = mutableMapOf<Any?, Any?>(
                SSL_MODE_PROPERTY to mode,
                "hibernate.connection.username" to "test",
                "hibernate.connection.password" to "test",
            )
            trustCertificate?.let { properties[TRUST_CERTIFICATE_PROPERTY] = it }
            properties.putAll(additionalProperties)

            return SslAwareSqlClientPoolConfiguration().apply {
                configure(properties)
            }
        }
    }
}
