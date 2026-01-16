package io.clroot.hibernate.reactive

import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import org.hibernate.reactive.mutiny.Mutiny

/**
 * ReadOnly 컨텍스트에서 쓰기 시도 시 발생하는 예외
 */
class ReadOnlyTransactionException(
    message: String,
) : IllegalStateException(message)

/**
 * Adapter에서 Hibernate Reactive Session을 획득할 때 사용하는 헬퍼.
 *
 * CoroutineContext에 세션이 있으면 재사용하고, 없으면 새로 생성합니다.
 * 이를 통해 Service에서 `tx.transactional { }` 블록으로 감싸면
 * 여러 Adapter 호출이 동일한 트랜잭션을 공유할 수 있습니다.
 *
 * 사용 예:
 * ```kotlin
 * class MyPersistenceAdapter(
 *     private val sessions: ReactiveSessionProvider,
 * ) : LoadPort, SavePort {
 *
 *     override suspend fun findById(id: Id): Entity? =
 *         sessions.read { session ->
 *             session.find(EntityClass::class.java, id.value)
 *         }
 *
 *     override suspend fun save(entity: Entity): Entity =
 *         sessions.write { session ->
 *             session.persist(entity).replaceWith(entity)
 *         }
 * }
 * ```
 */
class ReactiveSessionProvider(
    private val sessionFactory: Mutiny.SessionFactory,
) {
    /**
     * 읽기 전용 작업을 수행합니다.
     *
     * - 컨텍스트에 세션이 있으면 재사용
     * - 없으면 `withSession`으로 새 세션 생성
     */
    suspend fun <T> read(block: (Mutiny.Session) -> Uni<T>): T {
        val context = currentContextOrNull()
        return if (context != null) {
            block(context.session).awaitSuspending()
        } else {
            sessionFactory
                .withSession { session ->
                    block(session)
                }.awaitSuspending()
        }
    }

    /**
     * 쓰기 작업을 수행합니다.
     *
     * - 컨텍스트에 세션이 있으면 재사용
     * - 없으면 `withTransaction`으로 새 트랜잭션 생성
     *
     * @throws io.clroot.hibernate.reactive.ReadOnlyTransactionException readOnly 컨텍스트 내에서 호출 시
     */
    suspend fun <T> write(block: (Mutiny.Session) -> Uni<T>): T {
        val context = currentContextOrNull()
        return when {
            context?.isReadOnly == true -> {
                throw ReadOnlyTransactionException(
                    "Cannot perform write operation in read-only transaction. " +
                            "Use tx.transactional {} instead of tx.readOnly {}",
                )
            }
            context != null -> {
                block(context.session).awaitSuspending()
            }
            else -> {
                sessionFactory
                    .withTransaction { session ->
                        block(session)
                    }.awaitSuspending()
            }
        }
    }
}
