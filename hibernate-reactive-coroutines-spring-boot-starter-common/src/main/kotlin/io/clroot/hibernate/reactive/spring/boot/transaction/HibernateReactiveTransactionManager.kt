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
 * Hibernate Reactive (Mutiny API)를 위한 Spring ReactiveTransactionManager 구현체.
 *
 * Spring의 @Transactional 어노테이션과 통합하여 suspend 함수에서 선언적 트랜잭션 관리를 지원합니다.
 *
 * 이 트랜잭션 매니저는 [ReactiveSessionContext]와 통합되어,
 * @Transactional 컨텍스트 내에서 Repository가 자동으로 현재 세션을 인식합니다.
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
     * 새 세션을 열고 트랜잭션을 시작합니다.
     * Hibernate Reactive가 내부적으로 관리하는 Vert.x Context에서 세션이 생성되며,
     * 콜백에서 해당 Context를 캡처하여 저장합니다.
     */
    private fun openSessionAndBeginTransaction(
        txObject: HibernateTransactionObject,
        definition: TransactionDefinition,
        synchronizationManager: TransactionSynchronizationManager,
    ): Mono<Void> {
        return sessionFactory.openSession()
            .invoke { session ->
                log.debug("Acquired Mutiny.Session [{}] for Hibernate Reactive transaction", session)

                // 세션이 생성된 후, 현재 스레드는 Hibernate Reactive의 Vert.x EventLoop 스레드
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

        // 참여 트랜잭션에서 rollback-only가 설정되었으면 UnexpectedRollbackException 발생
        // Spring 표준 동작: 내부 트랜잭션이 rollback-only로 마킹되면 외부 커밋 시 예외 발생
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

        // 커밋 전에 남은 deadline을 statement timeout에 반영하고 영속성 컨텍스트를 flush합니다.
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
            // 리소스 언바인딩
            if (txObject.isNewSessionHolder()) {
                synchronizationManager.unbindResource(sessionFactory)
            }

            // 세션 정리
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

    /**
     * Vert.x Context에서 작업을 실행합니다.
     * Hibernate Reactive 세션 작업은 반드시 세션이 생성된 Vert.x EventLoop 스레드에서 실행되어야 합니다.
     */
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

            // block()이 동기적으로 던지면 sink가 종료되지 않아 커밋/롤백이 영원히 대기하게 된다.
            // 세션에서 ReactiveConnection을 꺼내는 리플렉션이 대표적인 동기 실패 지점이다.
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

    /**
     * 트랜잭션 객체 - 내부 상태 관리
     */
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
