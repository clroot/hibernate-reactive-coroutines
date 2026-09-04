package io.clroot.hibernate.reactive.spring.boot.transaction

import io.clroot.hibernate.reactive.ReactiveSessionContext
import io.clroot.hibernate.reactive.MonotonicClock
import io.clroot.hibernate.reactive.SystemMonotonicClock
import io.clroot.hibernate.reactive.TransactionMode
import io.vertx.core.Context
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineDispatcher
import org.hibernate.reactive.mutiny.Mutiny
import org.springframework.transaction.support.ResourceHolderSupport
import kotlin.time.Duration

/**
 * Spring-managed Mutiny session state, including the owning Vert.x context and transaction metadata.
 */
public class MutinySessionHolder(
    private var session: Mutiny.Session?,
    private var vertxContext: Context? = null,
    private var mode: TransactionMode = TransactionMode.READ_WRITE,
    private var timeout: Duration = Duration.INFINITE,
    private var startTimeNanos: Long = System.nanoTime(),
) : ResourceHolderSupport() {

    private var clock: MonotonicClock = SystemMonotonicClock

    /** Constructor seam for deterministic timeout tests without changing the 2.0.0 public ABI. */
    internal constructor(
        session: Mutiny.Session?,
        vertxContext: Context? = null,
        mode: TransactionMode = TransactionMode.READ_WRITE,
        timeout: Duration = Duration.INFINITE,
        clock: MonotonicClock,
        startTimeNanos: Long = clock.nanoTime(),
    ) : this(session, vertxContext, mode, timeout, startTimeNanos) {
        this.clock = clock
    }

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

    /** Returns the dispatcher for the session's owning Vert.x context, when available. */
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
        this.startTimeNanos = clock.nanoTime()
        this.transactionTimedOut = false
    }

    internal fun markTransactionTimedOut() {
        transactionTimedOut = true
        setRollbackOnly()
    }

    internal fun isTransactionTimedOut(): Boolean = transactionTimedOut

    /** Creates the session context propagated through coroutine execution. */
    public fun toReactiveSessionContext(): ReactiveSessionContext {
        return ReactiveSessionContext(
            session = getSession(),
            mode = mode,
            timeout = timeout,
            startTimeNanos = startTimeNanos,
            clock = clock,
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
