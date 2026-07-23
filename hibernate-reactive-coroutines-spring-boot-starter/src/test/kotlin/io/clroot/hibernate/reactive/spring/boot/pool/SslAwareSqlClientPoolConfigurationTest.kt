package io.clroot.hibernate.reactive.spring.boot.pool

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.vertx.core.net.PemTrustOptions
import io.vertx.pgclient.PgConnectOptions
import io.vertx.pgclient.SslMode
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
        options.isTrustAll shouldBe false
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
        (options.trustOptions as PemTrustOptions).certPaths shouldContainExactly
            listOf("/run/secrets/postgres-ca.pem")
        options.isTrustAll shouldBe false
    }

    test("verify-full enables hostname verification") {
        val options = sslConfiguration(
            mode = "verify-full",
            trustCertificate = "/run/secrets/postgres-ca.pem",
        ).connectOptions(POSTGRES_URI) as PgConnectOptions

        options.sslMode shouldBe SslMode.VERIFY_FULL
        options.hostnameVerificationAlgorithm shouldBe "HTTPS"
    }
}) {
    companion object {
        private val POSTGRES_URI = URI("postgresql://localhost:5432/test")
        private const val SSL_MODE_PROPERTY = "hibernate.vertx.pool.ssl.mode"
        private const val TRUST_CERTIFICATE_PROPERTY = "hibernate.vertx.pool.ssl.trust-certificate"

        private fun sslConfiguration(
            mode: String,
            trustCertificate: String? = null,
        ): SslAwareSqlClientPoolConfiguration {
            val properties = mutableMapOf<Any?, Any?>(
                SSL_MODE_PROPERTY to mode,
                "hibernate.connection.username" to "test",
                "hibernate.connection.password" to "test",
            )
            trustCertificate?.let { properties[TRUST_CERTIFICATE_PROPERTY] = it }

            return SslAwareSqlClientPoolConfiguration().apply {
                configure(properties)
            }
        }
    }
}
