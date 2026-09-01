package io.clroot.hibernate.reactive.spring.boot.transaction

import io.smallrye.mutiny.Uni
import org.hibernate.reactive.mutiny.Mutiny
import org.hibernate.reactive.pool.ReactiveConnection
import org.springframework.transaction.TransactionTimedOutException
import java.util.Locale
import java.util.concurrent.CompletionStage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.INFINITE

/** Applies the remaining Spring transaction deadline to database statements where supported. */
internal object TransactionTimeoutConfigurer {

    fun configure(session: Mutiny.Session, remainingTimeout: Duration): Uni<Void> =
        when {
            remainingTimeout == INFINITE -> Uni.createFrom().voidItem()
            remainingTimeout <= Duration.ZERO -> Uni.createFrom().failure(timeoutException())
            else ->
                try {
                    configure(ReactiveConnectionAccessor.get(session), remainingTimeout)
                } catch (error: Throwable) {
                    Uni.createFrom().failure(error)
                }
        }

    fun configure(connection: ReactiveConnection, remainingTimeout: Duration): Uni<Void> =
        try {
            when {
                remainingTimeout == INFINITE -> Uni.createFrom().voidItem()
                remainingTimeout <= Duration.ZERO -> Uni.createFrom().failure(timeoutException())
                isPostgreSql(connection) ->
                    connection.executeUnprepared(
                        "SET LOCAL statement_timeout = ${ceilMilliseconds(remainingTimeout)}",
                    ).asUni()

                else -> Uni.createFrom().voidItem()
            }
        } catch (error: Throwable) {
            Uni.createFrom().failure(error)
        }

    private fun isPostgreSql(connection: ReactiveConnection): Boolean =
        "postgresql" in connection.databaseMetadata.productName().lowercase(Locale.ROOT)

    private fun ceilMilliseconds(timeout: Duration): Long {
        val nanoseconds = timeout.inWholeNanoseconds
        val wholeMilliseconds = nanoseconds / NANOS_PER_MILLISECOND
        return (wholeMilliseconds + if (nanoseconds % NANOS_PER_MILLISECOND == 0L) 0 else 1)
            .coerceAtLeast(1)
    }

    private fun timeoutException(): TransactionTimedOutException =
        TransactionTimedOutException("Hibernate Reactive transaction exceeded its configured timeout")

    private fun <T> CompletionStage<T>.asUni(): Uni<T> = Uni.createFrom().completionStage(this)

    private const val NANOS_PER_MILLISECOND = 1_000_000L
}
