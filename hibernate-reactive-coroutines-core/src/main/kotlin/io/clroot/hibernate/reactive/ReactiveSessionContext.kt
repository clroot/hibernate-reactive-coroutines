package io.clroot.hibernate.reactive

import kotlinx.coroutines.currentCoroutineContext
import org.hibernate.reactive.mutiny.Mutiny
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.nanoseconds

/** Monotonic nanosecond clock used to evaluate transaction deadlines. */
@InternalHrcApi
public fun interface MonotonicClock {
    /** Returns an arbitrary monotonic nanosecond reading. */
    public fun nanoTime(): Long
}

/** Production monotonic clock backed by [System.nanoTime]. */
@InternalHrcApi
public object SystemMonotonicClock : MonotonicClock {
    override fun nanoTime(): Long = System.nanoTime()
}

/**
 * Transaction mode for a session in the coroutine context.
 */
@InternalHrcApi
public enum class TransactionMode {
    READ_ONLY,
    READ_WRITE,
}

/**
 * Holds a Hibernate Reactive session and transaction metadata in the [CoroutineContext].
 *
 * Nested operations reuse this session while the transaction block is active.
 *
 * @param session Hibernate Reactive session
 * @param mode transaction mode
 * @param timeout transaction timeout; unbounded by default
 * @param startTimeNanos transaction start reading from a monotonic clock
 */
@InternalHrcApi
public class ReactiveSessionContext(
    public val session: Mutiny.Session,
    public val mode: TransactionMode,
    public val timeout: Duration = INFINITE,
    public val startTimeNanos: Long = System.nanoTime(),
) : AbstractCoroutineContextElement(ReactiveSessionContext) {
    private var clock: MonotonicClock = SystemMonotonicClock

    /**
     * Creates a context with an explicit monotonic clock.
     *
     * The original four-argument constructor remains the primary constructor so binaries compiled
     * against HRC 2.0.0 keep resolving its default-argument bridge methods.
     */
    public constructor(
        session: Mutiny.Session,
        mode: TransactionMode,
        timeout: Duration = INFINITE,
        clock: MonotonicClock,
        startTimeNanos: Long = clock.nanoTime(),
    ) : this(session, mode, timeout, startTimeNanos) {
        this.clock = clock
    }

    public companion object Key : CoroutineContext.Key<ReactiveSessionContext>

    public val isReadOnly: Boolean get() = mode == TransactionMode.READ_ONLY

    /**
     * Returns the remaining timeout for nested operations.
     *
     * Uses a monotonic clock so wall-clock adjustments cannot extend a timeout.
     */
    public fun remainingTimeout(): Duration {
        if (timeout == INFINITE) return INFINITE
        val elapsedNanos = clock.nanoTime() - startTimeNanos
        val remainingNanos = timeout.inWholeNanoseconds - elapsedNanos
        return if (remainingNanos > 0) remainingNanos.nanoseconds else Duration.ZERO
    }
}

/**
 * Returns the session in the current coroutine context, if any.
 */
public suspend fun currentSessionOrNull(): Mutiny.Session? =
    currentCoroutineContext()[ReactiveSessionContext]?.session

/**
 * Returns the reactive session context, if any.
 */
@InternalHrcApi
public suspend fun currentContextOrNull(): ReactiveSessionContext? =
    currentCoroutineContext()[ReactiveSessionContext]
