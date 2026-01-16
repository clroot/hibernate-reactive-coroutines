package io.clroot.hibernate.reactive.spring.boot.transaction

import io.clroot.hibernate.reactive.ReactiveSessionContext
import io.clroot.hibernate.reactive.TransactionMode
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.converters.uni.UniReactorConverters
import io.vertx.core.Context
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineDispatcher
import org.hibernate.reactive.mutiny.Mutiny
import org.hibernate.reactive.pool.ReactiveConnection
import org.hibernate.reactive.session.ReactiveConnectionSupplier
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.reactive.AbstractReactiveTransactionManager
import org.springframework.transaction.reactive.GenericReactiveTransaction
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import reactor.core.publisher.Mono
import reactor.util.context.Context as ReactorContext
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/**
 * Hibernate Reactive (Mutiny API)를 위한 Spring ReactiveTransactionManager 구현체.
 *
 * Spring의 @Transactional 어노테이션과 통합하여 suspend 함수에서 선언적 트랜잭션 관리를 지원합니다.
 *
 * 이 트랜잭션 매니저는 [ReactiveSessionContext]와 통합되어,
 * @Transactional 컨텍스트 내에서 Repository가 자동으로 현재 세션을 인식합니다.
 */
class HibernateReactiveTransactionManager(
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
                val session = txObject.getSessionHolder().getSession()
                val vertxContext = txObject.getSessionHolder().getVertxContext()

                runOnVertxContext(vertxContext) {
                    val reactiveConnection = getReactiveConnection(session)
                    Uni.createFrom().completionStage(reactiveConnection.beginTransaction())
                        .convert().with(UniReactorConverters.toMono())
                        .doOnSuccess {
                            session.isDefaultReadOnly = definition.isReadOnly
                            txObject.getSessionHolder().setTransactionActive(true)
                            applyTimeout(txObject, definition)
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
                val timeout = if (definition.timeout != TransactionDefinition.TIMEOUT_DEFAULT) {
                    definition.timeout.seconds
                } else {
                    kotlin.time.Duration.INFINITE
                }
                val mode = if (definition.isReadOnly) TransactionMode.READ_ONLY else TransactionMode.READ_WRITE

                val holder = MutinySessionHolder(session, vertxContext, mode, timeout)
                txObject.setSessionHolder(holder, true)
            }
            .chain { session ->
                val reactiveConnection = getReactiveConnection(session)
                Uni.createFrom().completionStage(reactiveConnection.beginTransaction())
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

    private fun applyTimeout(txObject: HibernateTransactionObject, definition: TransactionDefinition) {
        if (definition.timeout != TransactionDefinition.TIMEOUT_DEFAULT) {
            val timeout = Duration.ofSeconds(definition.timeout.toLong())
            txObject.getSessionHolder().setTimeoutInMillis(timeout.toMillis())
        }
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
        if (sessionHolder.isRollbackOnly) {
            return runOnVertxContext(sessionHolder.getVertxContext()) {
                val reactiveConnection = getReactiveConnection(session)
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
            // Dirty Checking: 커밋 전에 flush하여 변경된 엔티티를 DB에 반영
            session.flush()
                .chain { _ ->
                    val reactiveConnection = getReactiveConnection(session)
                    Uni.createFrom().completionStage(reactiveConnection.commitTransaction())
                }
                .convert().with(UniReactorConverters.toMono())
        }
    }

    override fun doRollback(
        synchronizationManager: TransactionSynchronizationManager,
        status: GenericReactiveTransaction,
    ): Mono<Void> {
        val txObject = status.transaction as HibernateTransactionObject
        val sessionHolder = txObject.getSessionHolder()
        val session = sessionHolder.getSession()

        log.debug("Rolling back Hibernate Reactive transaction on Session [{}]", session)

        return runOnVertxContext(sessionHolder.getVertxContext()) {
            val reactiveConnection = getReactiveConnection(session)
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
            return block()
        }

        return Mono.create { sink ->
            vertxContext.runOnContext {
                block()
                    .subscribe(
                        { value -> sink.success(value) },
                        { error -> sink.error(error) },
                        { sink.success() },
                    )
            }
        }
    }

    /**
     * Mutiny.Session에서 ReactiveConnection을 추출합니다.
     *
     * **WARNING: 내부 구현 의존성**
     *
     * 이 메서드는 Hibernate Reactive의 내부 구현에 의존하는 리플렉션을 사용합니다.
     * MutinySessionImpl의 'delegate' 필드에 접근하여 ReactiveConnection을 추출합니다.
     *
     * 이 접근 방식은 다음과 같은 위험이 있습니다:
     * - Hibernate Reactive 버전 업그레이드 시 내부 구조 변경으로 인해 깨질 수 있음
     * - 'delegate' 필드명이나 클래스 구조가 변경될 경우 런타임 오류 발생
     *
     * 테스트된 버전: Hibernate Reactive 3.1.0.Final
     * 의존성 업그레이드 시 이 메서드의 동작을 반드시 검증하세요.
     */
    private fun getReactiveConnection(session: Mutiny.Session): ReactiveConnection {
        // 먼저 직접 캐스팅 시도
        if (session is ReactiveConnectionSupplier) {
            return session.reactiveConnection
        }

        // MutinySessionImpl의 delegate 필드에 리플렉션으로 접근 (내부 구현 의존)
        return try {
            val delegateField = session.javaClass.getDeclaredField("delegate")
            delegateField.isAccessible = true
            val delegate = delegateField.get(session)

            if (delegate is ReactiveConnectionSupplier) {
                delegate.reactiveConnection
            } else {
                throw IllegalStateException(
                    "Cannot extract ReactiveConnection from ${session.javaClass.name}. " +
                            "Delegate ${delegate?.javaClass?.name} does not implement ReactiveConnectionSupplier",
                )
            }
        } catch (e: NoSuchFieldException) {
            throw IllegalStateException(
                "Cannot extract ReactiveConnection from ${session.javaClass.name}. No 'delegate' field found.",
                e,
            )
        }
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
