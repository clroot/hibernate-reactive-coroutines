package io.clroot.hibernate.reactive

import kotlin.time.Duration
import kotlin.time.Duration.Companion.INFINITE

/**
 * [ReactiveTransactionExecutor] 바깥에서 시작된 트랜잭션 정보.
 *
 * @param isReadOnly 읽기 전용 트랜잭션 여부
 * @param remainingTimeout 남은 타임아웃 (제한이 없으면 [INFINITE])
 */
public class AmbientTransaction(
    public val isReadOnly: Boolean,
    public val remainingTimeout: Duration = INFINITE,
)

/**
 * 현재 실행 컨텍스트에 이미 활성 트랜잭션이 있는지 확인하는 훅.
 *
 * 코어 모듈은 Spring에 의존하지 않으므로, Spring `@Transactional`이 시작한 트랜잭션은
 * 스타터가 제공하는 구현을 통해 감지합니다. 이 훅이 없으면 `@Transactional` 안에서
 * `tx.transactional {}`을 호출할 때 쓰이지 않는 세션과 트랜잭션이 하나 더 열립니다.
 */
public fun interface AmbientTransactionProbe {

    /**
     * 활성 트랜잭션이 있으면 그 정보를, 없으면 null을 반환합니다.
     */
    public suspend fun currentTransaction(): AmbientTransaction?
}
