package io.clroot.hibernate.reactive.spring.boot.transaction

import io.smallrye.mutiny.Uni
import org.hibernate.reactive.pool.ReactiveConnection
import org.springframework.transaction.InvalidIsolationLevelException
import org.springframework.transaction.TransactionDefinition
import java.util.Locale
import java.util.concurrent.CompletionStage

/**
 * Applies Spring transaction isolation to a Hibernate Reactive connection.
 *
 * PostgreSQL changes the active transaction, while MySQL and MariaDB configure the
 * next transaction. Keeping that ordering here prevents the transaction manager
 * from silently falling back to the database default.
 */
internal object TransactionIsolationConfigurer {

    fun begin(
        connection: ReactiveConnection,
        isolationLevel: Int,
    ): Uni<Void> =
        try {
            beginConfigured(connection, isolationLevel)
        } catch (error: Throwable) {
            Uni.createFrom().failure(error)
        }

    private fun beginConfigured(
        connection: ReactiveConnection,
        isolationLevel: Int,
    ): Uni<Void> {
        val isolationClause = isolationClause(isolationLevel) ?: return beginTransaction(connection)
        val productName = connection.databaseMetadata.productName()

        return when (databaseFamily(productName)) {
            DatabaseFamily.POSTGRESQL ->
                beginTransaction(connection)
                    .chain { _: Void? ->
                        connection.executeUnprepared("SET TRANSACTION ISOLATION LEVEL $isolationClause")
                            .asUni()
                            .onFailure()
                            .call { _: Throwable -> connection.rollbackTransaction().asUni() }
                    }

            DatabaseFamily.MYSQL ->
                readMySqlSessionIsolation(connection)
                    .chain { sessionIsolation ->
                        connection.executeUnprepared("SET TRANSACTION ISOLATION LEVEL $isolationClause")
                            .asUni()
                            .chain { _: Void? ->
                                beginTransaction(connection)
                                    .onFailure()
                                    .call { _: Throwable ->
                                        restoreMySqlSessionIsolation(connection, sessionIsolation)
                                    }
                            }
                    }

            null ->
                Uni.createFrom().failure(
                    InvalidIsolationLevelException(
                        "Transaction isolation level $isolationClause is not supported for database '$productName'",
                    ),
                )
        }
    }

    private fun beginTransaction(connection: ReactiveConnection): Uni<Void> =
        try {
            connection.beginTransaction().asUni()
        } catch (error: Throwable) {
            Uni.createFrom().failure(error)
        }

    private fun readMySqlSessionIsolation(connection: ReactiveConnection): Uni<String> =
        selectMySqlSessionIsolation(connection, "transaction_isolation")
            .onFailure()
            .recoverWithUni { _: Throwable -> selectMySqlSessionIsolation(connection, "tx_isolation") }

    private fun selectMySqlSessionIsolation(
        connection: ReactiveConnection,
        variableName: String,
    ): Uni<String> =
        connection.select("SELECT @@SESSION.$variableName")
            .asUni()
            .map { result ->
                if (!result.hasNext()) {
                    throw InvalidIsolationLevelException(
                        "Database did not return its current session isolation level",
                    )
                }

                normalizeMySqlIsolation(result.next().firstOrNull())
            }

    private fun normalizeMySqlIsolation(value: Any?): String {
        val normalizedValue = value
            ?.toString()
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.replace('-', ' ')
            ?.replace('_', ' ')

        return when (normalizedValue) {
            "READ UNCOMMITTED",
            "READ COMMITTED",
            "REPEATABLE READ",
            "SERIALIZABLE",
            -> normalizedValue

            else ->
                throw InvalidIsolationLevelException(
                    "Database returned unsupported session isolation level '$value'",
                )
        }
    }

    private fun restoreMySqlSessionIsolation(
        connection: ReactiveConnection,
        isolationClause: String,
    ): Uni<Void> =
        connection.executeUnprepared("SET SESSION TRANSACTION ISOLATION LEVEL $isolationClause")
            .asUni()

    private fun isolationClause(isolationLevel: Int): String? =
        when (isolationLevel) {
            TransactionDefinition.ISOLATION_DEFAULT -> null
            TransactionDefinition.ISOLATION_READ_UNCOMMITTED -> "READ UNCOMMITTED"
            TransactionDefinition.ISOLATION_READ_COMMITTED -> "READ COMMITTED"
            TransactionDefinition.ISOLATION_REPEATABLE_READ -> "REPEATABLE READ"
            TransactionDefinition.ISOLATION_SERIALIZABLE -> "SERIALIZABLE"
            else ->
                throw InvalidIsolationLevelException(
                    "Unsupported transaction isolation level value: $isolationLevel",
                )
        }

    private fun databaseFamily(productName: String): DatabaseFamily? {
        val normalizedName = productName.lowercase(Locale.ROOT)
        return when {
            "postgresql" in normalizedName -> DatabaseFamily.POSTGRESQL
            "mysql" in normalizedName || "mariadb" in normalizedName -> DatabaseFamily.MYSQL
            else -> null
        }
    }

    private fun <T> CompletionStage<T>.asUni(): Uni<T> = Uni.createFrom().completionStage(this)

    private enum class DatabaseFamily {
        POSTGRESQL,
        MYSQL,
    }
}
