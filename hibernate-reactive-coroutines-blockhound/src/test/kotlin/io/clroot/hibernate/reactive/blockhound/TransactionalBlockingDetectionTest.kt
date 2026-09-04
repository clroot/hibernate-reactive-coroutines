package io.clroot.hibernate.reactive.blockhound

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.currentSessionOrNull
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.smallrye.mutiny.coroutines.awaitSuspending
import org.hibernate.cfg.AvailableSettings
import org.hibernate.cfg.Configuration
import org.hibernate.reactive.mutiny.Mutiny
import org.hibernate.reactive.provider.ReactiveServiceRegistryBuilder
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import reactor.blockhound.BlockHound
import reactor.blockhound.BlockingOperationError

/**
 * Verifies BlockHound with [ReactiveTransactionExecutor] against a real database.
 *
 * The non-blocking query also verifies the required Hibernate Reactive and pg-client
 * allowlists do not produce false positives.
 */
class TransactionalBlockingDetectionTest : DescribeSpec({
    BlockHound.install()

    val container = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).apply {
        withDatabaseName("blockhound_test")
        withUsername("test")
        withPassword("test")
        withReuse(true)
    }

    lateinit var sessionFactory: org.hibernate.SessionFactory
    lateinit var tx: ReactiveTransactionExecutor

    beforeSpec {
        container.start()

        val configuration = Configuration()
            .setProperty(
                AvailableSettings.JAKARTA_JDBC_URL,
                container.jdbcUrl.removePrefix("jdbc:").substringBefore('?'),
            )
            .setProperty(AvailableSettings.JAKARTA_JDBC_USER, container.username)
            .setProperty(AvailableSettings.JAKARTA_JDBC_PASSWORD, container.password)
            .setProperty("hibernate.connection.pool_size", "2")

        val serviceRegistry = ReactiveServiceRegistryBuilder()
            .applySettings(configuration.properties)
            .build()

        sessionFactory = configuration.buildSessionFactory(serviceRegistry)
        tx = ReactiveTransactionExecutor(sessionFactory.unwrap(Mutiny.SessionFactory::class.java))
    }

    afterSpec {
        sessionFactory.close()
    }

    describe("BlockHound + ReactiveTransactionExecutor") {

        it("allows a non-blocking query to complete") {
            val result = tx.readOnly {
                currentSessionOrNull()
                    .shouldNotBeNull()
                    .createNativeQuery<Any>("select 1")
                    .singleResult
                    .awaitSuspending()
            }

            (result as Number).toInt() shouldBe 1
        }

        it("detects Thread.sleep in a transactional block") {
            val thrown = runCatching {
                tx.transactional {
                    Thread.sleep(50)
                }
            }.exceptionOrNull()

            thrown.shouldNotBeNull()
            generateSequence<Throwable>(thrown) { it.cause.takeIf { cause -> cause !== it } }
                .take(10)
                .any { it is BlockingOperationError } shouldBe true
        }
    }
})
