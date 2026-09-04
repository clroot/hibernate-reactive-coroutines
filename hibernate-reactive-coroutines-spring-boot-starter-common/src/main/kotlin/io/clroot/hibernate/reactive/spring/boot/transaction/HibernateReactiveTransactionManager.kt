package io.clroot.hibernate.reactive.spring.boot.transaction

import io.clroot.hibernate.reactive.ReactiveSessionContext
import io.clroot.hibernate.reactive.TransactionMode
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.converters.uni.UniReactorConverters
import io.vertx.core.Context
import io.vertx.core.Vertx
import org.hibernate.reactive.mutiny.Mutiny
import org.hibernate.reactive.pool.ReactiveConnection
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionTimedOutException
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.reactive.AbstractReactiveTransactionManager
import org.springframework.transaction.reactive.GenericReactiveTransaction
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import reactor.core.Disposables
import reactor.core.publisher.Mono
import kotlin.time.Duration.Companion.seconds

/**
 * Spring reactive transaction manager for Hibernate Reactive's Mutiny API.
 *
 * Binds the transaction session to Spring so repositories can reuse it within
 * an `@Transactional` suspending function.
 */
public class HibernateReactiveTransactionManager(
    private val sessionFactory: Mutiny.SessionFactory,
) : AbstractReactiveTransactionManager(), InitializingBean {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun afterPropertiesSet() {
        log.info("HibernateReactiveTransactionManager initialized with Mutiny.SessionFactory")
    }

    override fun doGetTransaction(synchronizationManager: TransactionSynchronizationManager): Any {
        val txObject = HibernateTransactionObject()
        val sessionHolder = synchronizationManager.getResource(sessionFactory) as? MutinySessionHolder
        txObject.setSessionHolder(sessionHolder, false)
        return txObject
    }

    override fun isExistingTransaction(transaction: Any): Boolean {
        val txObject = transaction as HibernateTransactionObject
        return txObject.isTransactionActive()
    }

    override fun doBegin(
        synchronizationManager: TransactionSynchronizationManager,
        transaction: Any,
        definition: TransactionDefinition,
    ): Mono<Void> {
        val txObject = transaction as HibernateTransactionObject

        return Mono.defer {
            if (!txObject.hasSessionHolder() || txObject.getSessionHolder().isSynchronizedWithTransaction) {
                openSessionAndBeginTransaction(txObject, definition, synchronizationManager)
            } else {
                txObject.getSessionHolder().isSynchronizedWithTransaction = true
                val sessionHolder = txObject.getSessionHolder()
                val session = sessionHolder.getSession()
                val vertxContext = sessionHolder.getVertxContext()
                sessionHolder.configureTransaction(transactionMode(definition), transactionTimeout(definition))

                runOnVertxContext(vertxContext) {
                    val reactiveConnection = ReactiveConnectionAccessor.get(session)
                    beginTransaction(sessionHolder, reactiveConnection, definition.isolationLevel)
                        .convert().with(UniReactorConverters.toMono())
                        .doOnSuccess {
                            session.isDefaultReadOnly = definition.isReadOnly
                            sessionHolder.setTransactionActive(true)
                        }
                }
            }
        }.then()
    }

    /**
     * Opens a session and captures its Vert.x context.
     *
     * Hibernate Reactive sessions must subsequently run on that context.
     */
    private fun openSessionAndBeginTransaction(
        txObject: HibernateTransactionObject,
        definition: TransactionDefinition,
        synchronizationManager: TransactionSynchronizationManager,
    ): Mono<Void> {
        return sessionFactory.openSession()
            .invoke { session ->
                log.debug("Acquired Mutiny.Session [{}] for Hibernate Reactive transaction", session)

                // The session is opened on Hibernate Reactive's Vert.x event-loop context.
                val vertxContext = Vertx.currentContext()
                val holder = MutinySessionHolder(
                    session,
                    vertxContext,
                    transactionMode(definition),
                    transactionTimeout(definition),
                )
                txObject.setSessionHolder(holder, true)
            }
            .chain { session ->
                val sessionHolder = txObject.getSessionHolder()
                val reactiveConnection = ReactiveConnectionAccessor.get(session)
                beginTransaction(sessionHolder, reactiveConnection, definition.isolationLevel)
                    .replaceWith(session)
            }
            .invoke { session ->
                session.isDefaultReadOnly = definition.isReadOnly
                txObject.getSessionHolder().setTransactionActive(true)
                synchronizationManager.bindResource(sessionFactory, txObject.getSessionHolder())
            }
            .convert().with(UniReactorConverters.toMono())
            .onErrorResume { error ->
                if (txObject.isNewSessionHolder() && txObject.hasSessionHolder()) {
                    txObject.getSessionHolder().getSession().close()
                        .convert().with(UniReactorConverters.toMono())
                        .doOnTerminate { txObject.setSessionHolder(null, false) }
                        .then(Mono.error(error))
                } else {
                    Mono.error(error)
                }
            }
            .then()
    }

    override fun doCommit(
        synchronizationManager: TransactionSynchronizationManager,
        status: GenericReactiveTransaction,
    ): Mono<Void> {
        val txObject = status.transaction as HibernateTransactionObject
        val sessionHolder = txObject.getSessionHolder()
        val session = sessionHolder.getSession()

        log.debug("Committing Hibernate Reactive transaction on Session [{}]", session)

        // Preserve Spring semantics: an inner participant marking rollback-only fails the outer commit.
        if (sessionHolder.isRollbackOnly && !hasTransactionTimedOut(sessionHolder)) {
            return runOnVertxContext(sessionHolder.getVertxContext()) {
                val reactiveConnection = ReactiveConnectionAccessor.get(session)
                Uni.createFrom().completionStage(reactiveConnection.rollbackTransaction())
                    .convert().with(UniReactorConverters.toMono())
            }.then(
                Mono.error(
                    UnexpectedRollbackException(
                        "Transaction rolled back because it has been marked as rollback-only",
                    ),
                ),
            )
        }

        return runOnVertxContext(sessionHolder.getVertxContext()) {
            val reactiveConnection = ReactiveConnectionAccessor.get(session)
            completeTransaction(sessionHolder, session, reactiveConnection)
                .convert().with(UniReactorConverters.toMono())
        }
    }

    internal fun completeTransaction(
        sessionHolder: MutinySessionHolder,
        session: Mutiny.Session,
        reactiveConnection: ReactiveConnection,
    ): Uni<Void> {
        if (hasTransactionTimedOut(sessionHolder)) {
            return rollbackTimedOutTransaction(sessionHolder, reactiveConnection)
        }

        // Apply the remaining deadline to statements before flushing pending changes.
        val remainingTimeout = sessionHolder.toReactiveSessionContext().remainingTimeout()
        return TransactionTimeoutConfigurer.configure(reactiveConnection, remainingTimeout)
            .chain { _: Void? -> session.flush() }
            .onFailure()
            .recoverWithUni { error ->
                if (hasTransactionTimedOut(sessionHolder)) {
                    rollbackTimedOutTransaction(sessionHolder, reactiveConnection, error)
                } else {
                    rollbackAfterCommitPreparationFailure(reactiveConnection, error)
                }
            }
            .chain { _: Void? ->
                if (hasTransactionTimedOut(sessionHolder)) {
                    rollbackTimedOutTransaction(sessionHolder, reactiveConnection)
                } else {
                    Uni.createFrom().completionStage(reactiveConnection.commitTransaction())
                        .onFailure()
                        .recoverWithUni { error ->
                            if (hasTransactionTimedOut(sessionHolder)) {
                                rollbackTimedOutTransaction(sessionHolder, reactiveConnection, error)
                            } else {
                                Uni.createFrom().failure(error)
                            }
                        }
                }
            }
    }

    private fun hasTransactionTimedOut(sessionHolder: MutinySessionHolder): Boolean =
        sessionHolder.isTransactionTimedOut() ||
                sessionHolder.toReactiveSessionContext().remainingTimeout() == kotlin.time.Duration.ZERO

    private fun rollbackTimedOutTransaction(
        sessionHolder: MutinySessionHolder,
        reactiveConnection: ReactiveConnection,
        cause: Throwable? = null,
    ): Uni<Void> {
        sessionHolder.markTransactionTimedOut()
        val timeout = if (cause == null) {
            TransactionTimedOutException(
                "Hibernate Reactive transaction exceeded its configured timeout",
            )
        } else {
            TransactionTimedOutException(
                "Hibernate Reactive transaction exceeded its configured timeout",
                cause,
            )
        }
        val error = UnexpectedRollbackException(
            "Hibernate Reactive transaction rolled back because its timeout expired",
            timeout,
        )
        return Uni.createFrom().completionStage(reactiveConnection.rollbackTransaction())
            .chain { _: Void? -> Uni.createFrom().failure(error) }
    }

    private fun rollbackAfterCommitPreparationFailure(
        reactiveConnection: ReactiveConnection,
        error: Throwable,
    ): Uni<Void> =
        Uni.createFrom().completionStage(reactiveConnection.rollbackTransaction())
            .chain { _: Void? -> Uni.createFrom().failure(error) }

    override fun doRollback(
        synchronizationManager: TransactionSynchronizationManager,
        status: GenericReactiveTransaction,
    ): Mono<Void> {
        val txObject = status.transaction as HibernateTransactionObject
        val sessionHolder = txObject.getSessionHolder()
        val session = sessionHolder.getSession()

        log.debug("Rolling back Hibernate Reactive transaction on Session [{}]", session)

        return runOnVertxContext(sessionHolder.getVertxContext()) {
            val reactiveConnection = ReactiveConnectionAccessor.get(session)
            Uni.createFrom().completionStage(reactiveConnection.rollbackTransaction())
                .convert().with(UniReactorConverters.toMono())
        }
    }

    override fun doSetRollbackOnly(
        synchronizationManager: TransactionSynchronizationManager,
        status: GenericReactiveTransaction,
    ): Mono<Void> {
        return Mono.fromRunnable {
            val txObject = status.transaction as HibernateTransactionObject
            log.debug(
                "Setting Hibernate Reactive transaction [{}] rollback-only",
                txObject.getSessionHolder().getSession(),
            )
            txObject.setRollbackOnly()
        }
    }

    override fun doSuspend(
        synchronizationManager: TransactionSynchronizationManager,
        transaction: Any,
    ): Mono<Any> {
        return Mono.defer {
            val txObject = transaction as HibernateTransactionObject
            txObject.setSessionHolder(null, false)
            val suspendedHolder = synchronizationManager.unbindResource(sessionFactory)
            Mono.justOrEmpty(suspendedHolder)
        }
    }

    override fun doResume(
        synchronizationManager: TransactionSynchronizationManager,
        transaction: Any?,
        suspendedResources: Any,
    ): Mono<Void> {
        return Mono.fromRunnable {
            synchronizationManager.bindResource(sessionFactory, suspendedResources)
        }
    }

    override fun doCleanupAfterCompletion(
        synchronizationManager: TransactionSynchronizationManager,
        transaction: Any,
    ): Mono<Void> {
        val txObject = transaction as HibernateTransactionObject

        return Mono.defer {
            if (txObject.isNewSessionHolder()) {
                synchronizationManager.unbindResource(sessionFactory)
            }

            if (txObject.isNewSessionHolder() && txObject.hasSessionHolder()) {
                val sessionHolder = txObject.getSessionHolder()
                val session = sessionHolder.getSession()
                log.debug("Releasing Mutiny.Session [{}] after transaction", session)
                runOnVertxContext(sessionHolder.getVertxContext()) {
                    session.close()
                        .convert().with(UniReactorConverters.toMono())
                }
            } else {
                Mono.empty()
            }
        }
    }

    /** Runs session work on the Vert.x context that owns the session. */
    private fun <T : Any> runOnVertxContext(vertxContext: Context?, block: () -> Mono<T>): Mono<T> {
        if (vertxContext == null) {
            return try {
                block()
            } catch (error: Throwable) {
                Mono.error(error)
            }
        }

        return Mono.create { sink ->
            val subscription = Disposables.swap()
            sink.onCancel(subscription)

            // Propagate synchronous failures so the sink terminates instead of hanging commit or rollback.
            try {
                vertxContext.runOnContext {
                    try {
                        subscription.update(
                            block().subscribe(
                                { value -> sink.success(value) },
                                { error -> sink.error(error) },
                                { sink.success() },
                            ),
                        )
                    } catch (error: Throwable) {
                        sink.error(error)
                    }
                }
            } catch (error: Throwable) {
                sink.error(error)
            }
        }
    }

    internal fun beginTransaction(
        sessionHolder: MutinySessionHolder,
        reactiveConnection: ReactiveConnection,
        isolationLevel: Int,
    ): Uni<Void> =
        TransactionIsolationConfigurer.begin(reactiveConnection, isolationLevel)
            .chain { _: Void? ->
                val remainingTimeout = sessionHolder.toReactiveSessionContext().remainingTimeout()
                TransactionTimeoutConfigurer.configure(reactiveConnection, remainingTimeout)
                    .onFailure()
                    .call { _: Throwable ->
                        Uni.createFrom().completionStage(reactiveConnection.rollbackTransaction())
                    }
            }

    private fun transactionMode(definition: TransactionDefinition): TransactionMode =
        if (definition.isReadOnly) TransactionMode.READ_ONLY else TransactionMode.READ_WRITE

    private fun transactionTimeout(definition: TransactionDefinition): kotlin.time.Duration =
        if (definition.timeout == TransactionDefinition.TIMEOUT_DEFAULT) {
            kotlin.time.Duration.INFINITE
        } else {
            definition.timeout.seconds
        }

    /** Holds transaction-local session state. */
    private class HibernateTransactionObject {
        private var _sessionHolder: MutinySessionHolder? = null
        private var _newSessionHolder: Boolean = false

        fun setSessionHolder(holder: MutinySessionHolder?, isNew: Boolean) {
            this._sessionHolder = holder
            this._newSessionHolder = isNew
        }

        fun getSessionHolder(): MutinySessionHolder {
            return _sessionHolder ?: throw IllegalStateException("No MutinySessionHolder available")
        }

        fun hasSessionHolder(): Boolean = _sessionHolder != null

        fun isNewSessionHolder(): Boolean = _newSessionHolder

        fun isTransactionActive(): Boolean = _sessionHolder?.isTransactionActive() == true

        fun setRollbackOnly() {
            _sessionHolder?.setRollbackOnly()
        }
    }
}
