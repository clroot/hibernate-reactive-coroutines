package io.clroot.hibernate.reactive

import kotlinx.coroutines.currentCoroutineContext
import org.hibernate.reactive.mutiny.Mutiny
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.milliseconds

/**
 * 트랜잭션 모드
 */
enum class TransactionMode {
    READ_ONLY,
    READ_WRITE,
}

/**
 * CoroutineContext에 Hibernate Reactive Session과 트랜잭션 메타데이터를 담는 Element.
 *
 * Service에서 `tx.transactional { }` 블록을 사용하면 이 컨텍스트가 생성되어
 * 하위 Adapter 호출에서 동일한 세션을 재사용할 수 있습니다.
 */
class ReactiveSessionContext(
    val session: Mutiny.Session,
    val mode: TransactionMode,
    val timeout: Duration = INFINITE,
    val startTime: Long = System.currentTimeMillis(),
) : AbstractCoroutineContextElement(ReactiveSessionContext) {
    companion object Key : CoroutineContext.Key<ReactiveSessionContext>

    val isReadOnly: Boolean get() = mode == TransactionMode.READ_ONLY

    /**
     * 남은 타임아웃 시간 계산.
     * 중첩 트랜잭션에서 부모의 남은 시간을 상속받을 때 사용.
     */
    fun remainingTimeout(): Duration {
        if (timeout == INFINITE) return INFINITE
        val elapsed = System.currentTimeMillis() - startTime
        val remaining = timeout.inWholeMilliseconds - elapsed
        return if (remaining > 0) remaining.milliseconds else Duration.ZERO
    }
}

/**
 * 현재 CoroutineContext에서 Session을 가져옵니다.
 * 컨텍스트가 없으면 null 반환.
 */
suspend fun currentSessionOrNull(): Mutiny.Session? =
    currentCoroutineContext()[ReactiveSessionContext]?.session

/**
 * 현재 CoroutineContext에서 ReactiveSessionContext를 가져옵니다.
 * 컨텍스트가 없으면 null 반환.
 */
suspend fun currentContextOrNull(): ReactiveSessionContext? =
    currentCoroutineContext()[ReactiveSessionContext]
