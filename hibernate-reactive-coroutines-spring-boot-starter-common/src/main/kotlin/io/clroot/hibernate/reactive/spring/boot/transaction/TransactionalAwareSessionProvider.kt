package io.clroot.hibernate.reactive.spring.boot.transaction

import io.clroot.hibernate.reactive.ReadOnlyTransactionException
import io.clroot.hibernate.reactive.ReactiveSessionContext
import io.clroot.hibernate.reactive.ReactiveSessionOperations
import io.clroot.hibernate.reactive.currentContextOrNull
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.withContext
import org.hibernate.reactive.mutiny.Mutiny
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.TransactionTimedOutException
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import kotlin.reflect.KProperty1
import kotlin.time.Duration

/**
 * Resolves sessions in this order: the Spring `@Transactional` context, a coroutine
 * [ReactiveSessionContext], then a newly opened session.
 */
public open class TransactionalAwareSessionProvider(
    private val sessionFactory: Mutiny.SessionFactory,
) : ReactiveSessionOperations {
    internal val metamodel
        get() = sessionFactory.metamodel

    public override suspend fun <T> read(block: (Mutiny.Session) -> Uni<T>): T {
        val transactionalContext = getTransactionalSessionContext()
        if (transactionalContext != null) {
            return executeWithTransactionTimeout(transactionalContext, block)
        }

        val existingContext = currentContextOrNull()
        if (existingContext != null) {
            return block(existingContext.session).awaitSuspending()
        }

        return sessionFactory
            .withSession { session ->
                block(session)
            }.awaitSuspending()
    }

    /** @throws ReadOnlyTransactionException when called within a read-only transaction. */
    public override suspend fun <T> write(block: (Mutiny.Session) -> Uni<T>): T {
        val transactionalContext = getTransactionalSessionContext()
        if (transactionalContext != null) {
            if (transactionalContext.isReadOnly) {
                throw ReadOnlyTransactionException(
                    "Cannot perform write operation in read-only @Transactional. " +
                            "Remove readOnly=true from @Transactional annotation.",
                )
            }
            return executeWithTransactionTimeout(transactionalContext, block)
        }

        val existingContext = currentContextOrNull()
        return when {
            existingContext?.isReadOnly == true -> {
                throw ReadOnlyTransactionException(
                    "Cannot perform write operation in read-only transaction. " +
                            "Use tx.transactional {} instead of tx.readOnly {}",
                )
            }

            existingContext != null -> {
                block(existingContext.session).awaitSuspending()
            }

            else -> {
                sessionFactory
                    .withTransaction { session ->
                        block(session)
                    }.awaitSuspending()
            }
        }
    }

    /** Retrieves the session bound to the current Spring reactive transaction. */
    private suspend fun getTransactionalSessionContext(): TransactionalSessionInfo? {
        val reactorContext = currentCoroutineContext()[ReactorContext]?.context
            ?: return null

        return try {
            TransactionSynchronizationManager.forCurrentTransaction()
                .mapNotNull { tsm ->
                    if (!tsm.isActualTransactionActive) {
                        return@mapNotNull null
                    }
                    val holder = tsm.getResource(sessionFactory)
                    check(holder is MutinySessionHolder) {
                        "No Hibernate Reactive session is bound to the active Spring transaction"
                    }
                    TransactionalSessionInfo(
                        session = holder.getSession(),
                        sessionContext = holder.toReactiveSessionContext(),
                        holder = holder,
                        dispatcher = holder.getDispatcher(),
                    )
                }
                .contextWrite(reactorContext)
                .awaitSingleOrNull()
        } catch (_: NoTransactionException) {
            null
        }
    }

    private data class TransactionalSessionInfo(
        val session: Mutiny.Session,
        val sessionContext: ReactiveSessionContext,
        val holder: MutinySessionHolder,
        val dispatcher: kotlinx.coroutines.CoroutineDispatcher?,
    ) {
        val isReadOnly: Boolean
            get() = sessionContext.isReadOnly
    }

    private suspend fun <T> executeWithTransactionTimeout(
        transaction: TransactionalSessionInfo,
        block: (Mutiny.Session) -> Uni<T>,
    ): T {
        checkTransactionTimeout(transaction)
        try {
            val result = withContext(transaction.dispatcher ?: currentCoroutineContext()) {
                checkTransactionTimeout(transaction)
                val remainingTimeout = transaction.sessionContext.remainingTimeout()
                TransactionTimeoutConfigurer.configure(transaction.session, remainingTimeout)
                    .chain { _: Void? -> block(transaction.session) }
                    .awaitSuspending()
            }
            checkTransactionTimeout(transaction)
            return result
        } catch (error: Throwable) {
            if (transaction.sessionContext.remainingTimeout() == Duration.ZERO) {
                transaction.holder.markTransactionTimedOut()
                throw transactionTimedOutException(error)
            }
            throw error
        }
    }

    private fun checkTransactionTimeout(transaction: TransactionalSessionInfo) {
        if (transaction.sessionContext.remainingTimeout() == Duration.ZERO) {
            transaction.holder.markTransactionTimedOut()
            throw transactionTimedOutException()
        }
    }

    private fun transactionTimedOutException(cause: Throwable? = null): TransactionTimedOutException =
        if (cause == null) {
            TransactionTimedOutException("Hibernate Reactive transaction exceeded its configured timeout")
        } else {
            TransactionTimedOutException(
                "Hibernate Reactive transaction exceeded its configured timeout",
                cause,
            )
        }

    /** Fetches a lazy association for an entity attached to the current session. */
    public open suspend fun <E : Any, T> fetch(entity: E, property: KProperty1<E, T>): T {
        return read { session ->
            val association = property.get(entity)
            session.fetch(association)
        }
    }

    /** Fetches lazy associations sequentially using the current session. */
    public open suspend fun <E : Any> fetchAll(entity: E, vararg properties: KProperty1<E, *>) {
        read { session ->
            var chain: Uni<*> = Uni.createFrom().voidItem()
            for (property in properties) {
                val association = property.get(entity)
                chain = chain.chain { _ -> session.fetch(association) }
            }
            chain
        }
    }

    /** Merges a detached entity before fetching one of its lazy associations. */
    public open suspend fun <E : Any, T> fetchFromDetached(
        entity: E,
        entityClass: Class<E>,
        property: KProperty1<E, T>,
    ): T {
        return read { session ->
            session.merge(entity)
                .chain { managedEntity ->
                    val association = property.get(managedEntity)
                    session.fetch(association)
                }
        }
    }
}
