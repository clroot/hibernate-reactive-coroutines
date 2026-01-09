package io.clroot.hibernate.reactive

import io.smallrye.mutiny.coroutines.asUni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.*
import org.hibernate.reactive.mutiny.Mutiny
import kotlin.time.Duration
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.seconds

/**
 * Service에서 여러 Port 호출을 하나의 트랜잭션으로 묶을 때 사용하는 래퍼.
 *
 * 사용 예:
 * ```kotlin
 * class MyService(
 *     private val tx: ReactiveTransactionExecutor,
 *     private val saveAPort: SaveAPort,
 *     private val saveBPort: SaveBPort,
 * ) {
 *     suspend fun doSomething() = tx.transactional {
 *         // 하나의 트랜잭션에서 실행됨
 *         saveAPort.save(a)
 *         saveBPort.save(b)
 *         // 예외 발생 시 둘 다 롤백됨
 *     }
 *
 *     suspend fun readSomething() = tx.readOnly {
 *         loadPort.findById(id)
 *     }
 * }
 * ```
 *
 * 주의사항:
 * - 블록 내에서 `launch`/`async`로 코루틴을 탈출시키면 세션 유효성 문제 발생
 * - `withContext(Dispatchers.Default)` 등으로 스레드를 전환하면 세션이 무효화될 수 있음
 * - 트랜잭션 내에서 외부 I/O(HTTP 호출 등)를 수행하면 DB 커넥션이 오래 점유됨
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReactiveTransactionExecutor(
    private val sessionFactory: Mutiny.SessionFactory,
) {
    companion object {
        val DEFAULT_TIMEOUT: Duration = 30.seconds
    }

    /**
     * 쓰기 트랜잭션을 시작합니다.
     *
     * 블록 내 모든 Port 호출이 동일한 세션을 공유하며,
     * 이미 트랜잭션 컨텍스트 안에 있으면 기존 세션을 재사용합니다 (중첩 안전).
     *
     * @param timeout 트랜잭션 타임아웃 (기본 30초). 중첩 시 부모의 남은 시간과 비교하여 더 짧은 값 적용.
     */
    suspend fun <T> transactional(
        timeout: Duration = DEFAULT_TIMEOUT,
        block: suspend () -> T,
    ): T {
        val parentContext = currentContextOrNull()

        val effectiveTimeout = calculateEffectiveTimeout(parentContext, timeout)

        return if (parentContext != null) {
            // 기존 세션 재사용 (REQUIRED 동작)
            executeWithTimeout(effectiveTimeout) { block() }
        } else {
            // 새 트랜잭션 생성 - Vert.x EventLoop에서 실행
            executeWithTimeout(effectiveTimeout) {
                sessionFactory
                    .withTransaction { session ->
                        // 현재 Vert.x Context의 디스패처를 사용하여 스레드 일관성 유지
                        val vertxDispatcher = Vertx.currentContext().dispatcher()
                        CoroutineScope(vertxDispatcher)
                            .async {
                                val newContext =
                                    ReactiveSessionContext(
                                        session = session,
                                        mode = TransactionMode.READ_WRITE,
                                        timeout = effectiveTimeout,
                                    )
                                withContext(newContext) {
                                    block()
                                }
                            }.asUni()
                    }.awaitSuspending()
            }
        }
    }

    /**
     * 읽기 전용 세션을 시작합니다.
     *
     * 블록 내 모든 Port 호출이 동일한 세션을 공유하며,
     * 이미 세션 컨텍스트 안에 있으면 기존 세션을 재사용합니다 (중첩 안전).
     *
     * @param timeout 세션 타임아웃 (기본 30초). 중첩 시 부모의 남은 시간과 비교하여 더 짧은 값 적용.
     */
    suspend fun <T> readOnly(
        timeout: Duration = DEFAULT_TIMEOUT,
        block: suspend () -> T,
    ): T {
        val parentContext = currentContextOrNull()

        val effectiveTimeout = calculateEffectiveTimeout(parentContext, timeout)

        return if (parentContext != null) {
            // 기존 세션 재사용
            executeWithTimeout(effectiveTimeout) { block() }
        } else {
            // 새 읽기 전용 세션 생성 - Vert.x EventLoop에서 실행
            executeWithTimeout(effectiveTimeout) {
                sessionFactory
                    .withSession { session ->
                        // 현재 Vert.x Context의 디스패처를 사용하여 스레드 일관성 유지
                        val vertxDispatcher = Vertx.currentContext().dispatcher()
                        CoroutineScope(vertxDispatcher)
                            .async {
                                val newContext =
                                    ReactiveSessionContext(
                                        session = session,
                                        mode = TransactionMode.READ_ONLY,
                                        timeout = effectiveTimeout,
                                    )
                                withContext(newContext) {
                                    block()
                                }
                            }.asUni()
                    }.awaitSuspending()
            }
        }
    }

    private fun calculateEffectiveTimeout(
        parentContext: ReactiveSessionContext?,
        timeout: Duration,
    ): Duration {
        if (parentContext == null) return timeout

        val remaining = parentContext.remainingTimeout()
        return when {
            remaining == INFINITE -> timeout
            timeout == INFINITE -> remaining
            else -> minOf(timeout, remaining)
        }
    }

    private suspend inline fun <T> executeWithTimeout(
        timeout: Duration,
        crossinline block: suspend () -> T,
    ): T =
        if (timeout == INFINITE) {
            block()
        } else {
            withTimeout(timeout) { block() }
        }
}
