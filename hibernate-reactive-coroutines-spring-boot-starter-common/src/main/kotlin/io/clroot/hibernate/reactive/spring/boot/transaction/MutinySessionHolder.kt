package io.clroot.hibernate.reactive.spring.boot.transaction

import io.clroot.hibernate.reactive.ReactiveSessionContext
import io.clroot.hibernate.reactive.TransactionMode
import io.vertx.core.Context
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineDispatcher
import org.hibernate.reactive.mutiny.Mutiny
import org.springframework.transaction.support.ResourceHolderSupport
import kotlin.time.Duration

/**
 * Mutiny.Session을 래핑하여 Spring TransactionSynchronizationManager에서 관리할 수 있도록 합니다.
 * Vert.x Context와 트랜잭션 메타데이터도 함께 저장하여 [ReactiveSessionContext]와 통합합니다.
 */
public class MutinySessionHolder(
    private var session: Mutiny.Session?,
    private var vertxContext: Context? = null,
    private var mode: TransactionMode = TransactionMode.READ_WRITE,
    private var timeout: Duration = Duration.INFINITE,
    private var startTimeNanos: Long = System.nanoTime(),
) : ResourceHolderSupport() {

    private var transactionActive: Boolean = false
    private var transactionTimedOut: Boolean = false

    public fun getSession(): Mutiny.Session {
        return session ?: throw IllegalStateException("No Mutiny.Session available")
    }

    public fun hasSession(): Boolean = session != null

    public fun setSession(session: Mutiny.Session?) {
        this.session = session
    }

    public fun getVertxContext(): Context? = vertxContext

    public fun setVertxContext(context: Context?) {
        this.vertxContext = context
    }

    /**
     * 세션 작업에 사용할 CoroutineDispatcher를 반환합니다.
     * Vert.x Context가 있으면 해당 dispatcher를, 없으면 null을 반환합니다.
     */
    public fun getDispatcher(): CoroutineDispatcher? {
        return vertxContext?.dispatcher()
    }

    public fun setTransactionActive(active: Boolean) {
        this.transactionActive = active
    }

    public fun isTransactionActive(): Boolean = transactionActive

    internal fun configureTransaction(mode: TransactionMode, timeout: Duration) {
        this.mode = mode
        this.timeout = timeout
        this.startTimeNanos = System.nanoTime()
        this.transactionTimedOut = false
    }

    internal fun markTransactionTimedOut() {
        transactionTimedOut = true
        setRollbackOnly()
    }

    internal fun isTransactionTimedOut(): Boolean = transactionTimedOut

    /**
     * 현재 세션 홀더에서 ReactiveSessionContext를 생성합니다.
     * 코루틴 컨텍스트에 세션을 전파할 때 사용합니다.
     */
    public fun toReactiveSessionContext(): ReactiveSessionContext {
        return ReactiveSessionContext(
            session = getSession(),
            mode = mode,
            timeout = timeout,
            startTimeNanos = startTimeNanos,
        )
    }

    override fun clear() {
        super.clear()
        transactionActive = false
        transactionTimedOut = false
    }

    override fun released() {
        super.released()
        if (isVoid && session != null) {
            session = null
        }
    }
}
