package io.clroot.hibernate.reactive.spring.boot.transaction

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.vertx.sqlclient.spi.DatabaseMetadata
import org.hibernate.reactive.pool.ReactiveConnection
import org.springframework.transaction.TransactionTimedOutException
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class TransactionTimeoutConfigurerTest : DescribeSpec({

    fun connection(productName: String, statements: MutableList<String>): ReactiveConnection {
        val metadata = mockk<DatabaseMetadata>()
        val connection = mockk<ReactiveConnection>()
        every { metadata.productName() } returns productName
        every { connection.databaseMetadata } returns metadata
        every { connection.executeUnprepared(any()) } answers {
            statements += firstArg<String>()
            CompletableFuture.completedFuture(null)
        }
        return connection
    }

    describe("database statement timeout configuration") {
        it("applies the remaining timeout to PostgreSQL and rounds up partial milliseconds") {
            val statements = mutableListOf<String>()
            val connection = connection("PostgreSQL", statements)

            TransactionTimeoutConfigurer.configure(connection, 1_500_000_001.nanoseconds)
                .await().indefinitely()

            statements shouldContainExactly listOf("SET LOCAL statement_timeout = 1501")
        }

        it("does nothing for an infinite timeout without inspecting the database") {
            val connection = mockk<ReactiveConnection>()

            TransactionTimeoutConfigurer.configure(connection, INFINITE).await().indefinitely()

            verify(exactly = 0) { connection.databaseMetadata }
        }

        it("does not send PostgreSQL SQL to other databases") {
            val statements = mutableListOf<String>()
            val connection = connection("MySQL", statements)

            TransactionTimeoutConfigurer.configure(connection, 1.seconds).await().indefinitely()

            statements shouldBe emptyList()
        }

        it("rejects an already expired deadline instead of disabling PostgreSQL timeout") {
            val connection = mockk<ReactiveConnection>()

            shouldThrow<TransactionTimedOutException> {
                TransactionTimeoutConfigurer.configure(connection, kotlin.time.Duration.ZERO)
                    .await().indefinitely()
            }

            verify(exactly = 0) { connection.databaseMetadata }
        }

        it("propagates PostgreSQL timeout setup failures") {
            val statements = mutableListOf<String>()
            val connection = connection("PostgreSQL", statements)
            every { connection.executeUnprepared(any()) } answers {
                statements += firstArg<String>()
                CompletableFuture.failedFuture(IllegalStateException("setup failed"))
            }

            shouldThrow<IllegalStateException> {
                TransactionTimeoutConfigurer.configure(connection, 1.seconds).await().indefinitely()
            }.message shouldBe "setup failed"

            statements shouldContainExactly listOf("SET LOCAL statement_timeout = 1000")
        }
    }
})
