package io.clroot.hibernate.reactive.spring.boot.transaction

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.vertx.sqlclient.spi.DatabaseMetadata
import org.hibernate.reactive.pool.ReactiveConnection
import org.springframework.transaction.InvalidIsolationLevelException
import org.springframework.transaction.TransactionDefinition
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class TransactionIsolationConfigurerTest : DescribeSpec({

    fun completedVoid(): CompletionStage<Void> = CompletableFuture.completedFuture(null)

    fun connection(productName: String, events: MutableList<String>): ReactiveConnection {
        val metadata = mockk<DatabaseMetadata>()
        val connection = mockk<ReactiveConnection>()
        val isolationResult = mockk<ReactiveConnection.Result>()

        every { metadata.productName() } returns productName
        every { connection.databaseMetadata } returns metadata
        every { isolationResult.hasNext() } returns true
        every { isolationResult.next() } returns arrayOf("READ-COMMITTED")
        every { connection.select(any()) } answers {
            events += firstArg<String>()
            CompletableFuture.completedFuture(isolationResult)
        }
        every { connection.beginTransaction() } answers {
            events += "begin"
            completedVoid()
        }
        every { connection.executeUnprepared(any()) } answers {
            events += firstArg<String>()
            completedVoid()
        }
        every { connection.rollbackTransaction() } answers {
            events += "rollback"
            completedVoid()
        }

        return connection
    }

    describe("transaction isolation configuration") {
        it("begins directly when the database default is requested") {
            val events = mutableListOf<String>()
            val connection = connection("unknown", events)

            TransactionIsolationConfigurer.begin(
                connection,
                TransactionDefinition.ISOLATION_DEFAULT,
            ).await().indefinitely()

            events shouldContainExactly listOf("begin")
        }

        it("configures PostgreSQL after beginning the transaction") {
            val events = mutableListOf<String>()
            val connection = connection("PostgreSQL", events)

            TransactionIsolationConfigurer.begin(
                connection,
                TransactionDefinition.ISOLATION_REPEATABLE_READ,
            ).await().indefinitely()

            events shouldContainExactly
                listOf("begin", "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
        }

        it("configures MySQL and MariaDB before beginning the transaction") {
            listOf("MySQL", "MariaDB").forEach { productName ->
                val events = mutableListOf<String>()
                val connection = connection(productName, events)

                TransactionIsolationConfigurer.begin(
                    connection,
                    TransactionDefinition.ISOLATION_SERIALIZABLE,
                ).await().indefinitely()

                events shouldContainExactly
                    listOf(
                        "SELECT @@SESSION.transaction_isolation",
                        "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE",
                        "begin",
                    )
            }
        }

        it("restores the MySQL session isolation when begin fails") {
            val events = mutableListOf<String>()
            val connection = connection("MySQL", events)
            every { connection.beginTransaction() } answers {
                events += "begin"
                CompletableFuture.failedFuture(IllegalStateException("begin failed"))
            }

            shouldThrow<IllegalStateException> {
                TransactionIsolationConfigurer.begin(
                    connection,
                    TransactionDefinition.ISOLATION_SERIALIZABLE,
                ).await().indefinitely()
            }.message shouldBe "begin failed"

            events shouldContainExactly
                listOf(
                    "SELECT @@SESSION.transaction_isolation",
                    "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE",
                    "begin",
                    "SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED",
                )
        }

        it("restores the MySQL session isolation when begin throws synchronously") {
            val events = mutableListOf<String>()
            val connection = connection("MySQL", events)
            every { connection.beginTransaction() } answers {
                events += "begin"
                throw IllegalStateException("transaction already active")
            }

            shouldThrow<IllegalStateException> {
                TransactionIsolationConfigurer.begin(
                    connection,
                    TransactionDefinition.ISOLATION_REPEATABLE_READ,
                ).await().indefinitely()
            }.message shouldBe "transaction already active"

            events shouldContainExactly
                listOf(
                    "SELECT @@SESSION.transaction_isolation",
                    "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ",
                    "begin",
                    "SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED",
                )
        }

        it("rolls back PostgreSQL when applying isolation fails after begin") {
            val events = mutableListOf<String>()
            val connection = connection("PostgreSQL", events)
            every { connection.executeUnprepared(any()) } answers {
                events += firstArg<String>()
                CompletableFuture.failedFuture(IllegalStateException("isolation failed"))
            }

            shouldThrow<IllegalStateException> {
                TransactionIsolationConfigurer.begin(
                    connection,
                    TransactionDefinition.ISOLATION_SERIALIZABLE,
                ).await().indefinitely()
            }.message shouldBe "isolation failed"

            events shouldContainExactly
                listOf("begin", "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE", "rollback")
        }

        it("rejects explicit isolation for unsupported databases") {
            val events = mutableListOf<String>()
            val connection = connection("Oracle", events)

            shouldThrow<InvalidIsolationLevelException> {
                TransactionIsolationConfigurer.begin(
                    connection,
                    TransactionDefinition.ISOLATION_READ_COMMITTED,
                ).await().indefinitely()
            }

            events shouldContainExactly emptyList()
        }

        it("rejects unknown Spring isolation constants") {
            val events = mutableListOf<String>()
            val connection = connection("PostgreSQL", events)
            val result = TransactionIsolationConfigurer.begin(connection, Int.MAX_VALUE)

            shouldThrow<InvalidIsolationLevelException> {
                result.await().indefinitely()
            }

            events shouldContainExactly emptyList()
        }
    }
})
