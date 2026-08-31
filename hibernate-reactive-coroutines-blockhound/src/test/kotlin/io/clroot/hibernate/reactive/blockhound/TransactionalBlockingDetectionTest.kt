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
 * 실제 DB 위에서 BlockHound + ReactiveTransactionExecutor 조합을 검증하는 E2E 테스트.
 *
 * 두 가지를 보장합니다:
 * 1. 정상적인 논블로킹 쿼리가 BlockHound 아래에서 오탐 없이 통과한다
 *    (Hibernate Reactive / pg-client 내부의 정당한 호출에 대한 allowlist 검증)
 * 2. transactional 블록 안의 블로킹 호출은 BlockingOperationError로 탐지된다
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

        it("정상적인 논블로킹 쿼리는 BlockHound 아래에서 통과한다") {
            val result = tx.readOnly {
                currentSessionOrNull()
                    .shouldNotBeNull()
                    .createNativeQuery<Any>("select 1")
                    .singleResult
                    .awaitSuspending()
            }

            (result as Number).toInt() shouldBe 1
        }

        it("transactional 블록 안의 Thread.sleep은 BlockingOperationError로 탐지된다") {
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
