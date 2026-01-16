package io.clroot.hibernate.reactive

import io.smallrye.mutiny.Uni
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
    ): T = executeInSession(
        mode = TransactionMode.READ_WRITE,
        timeout = timeout,
        sessionStarter = { callback -> sessionFactory.withTransaction(callback) },
        block = block,
    )

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
    ): T = executeInSession(
        mode = TransactionMode.READ_ONLY,
        timeout = timeout,
        sessionStarter = { callback -> sessionFactory.withSession(callback) },
        block = block,
    )

    /**
     * 세션 컨텍스트 내에서 블록을 실행합니다.
     *
     * 이미 세션 컨텍스트 안에 있으면 기존 세션을 재사용하고 (REQUIRED 동작),
     * 없으면 새 세션을 생성합니다.
     *
     * @param mode 트랜잭션 모드 (READ_WRITE 또는 READ_ONLY)
     * @param timeout 타임아웃 (중첩 시 부모의 남은 시간과 비교하여 더 짧은 값 적용)
     * @param sessionStarter 새 세션을 시작하는 함수 (withTransaction 또는 withSession)
     * @param block 실행할 블록
     */
    private suspend fun <T> executeInSession(
        mode: TransactionMode,
        timeout: Duration,
        sessionStarter: (java.util.function.Function<Mutiny.Session, Uni<T>>) -> Uni<T>,
        block: suspend () -> T,
    ): T {
        val parentContext = currentContextOrNull()
        val effectiveTimeout = calculateEffectiveTimeout(parentContext, timeout)

        return if (parentContext != null) {
            // 기존 세션 재사용 (REQUIRED 동작)
            executeWithTimeout(effectiveTimeout) { block() }
        } else {
            // 새 세션 생성 - Vert.x EventLoop에서 실행
            executeWithTimeout(effectiveTimeout) {
                sessionStarter { session ->
                    val vertxDispatcher = requireVertxContext().dispatcher()
                    CoroutineScope(vertxDispatcher)
                        .async {
                            val newContext = ReactiveSessionContext(
                                session = session,
                                mode = mode,
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
     * 현재 Vert.x Context를 반환합니다.
     *
     * @throws IllegalStateException Vert.x Context가 없는 경우
     */
    private fun requireVertxContext(): io.vertx.core.Context {
        return Vertx.currentContext()
            ?: throw IllegalStateException(
                "ReactiveTransactionExecutor must be called within a Vert.x context. " +
                        "Ensure the application is running on Vert.x or within a reactive pipeline."
            )
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
