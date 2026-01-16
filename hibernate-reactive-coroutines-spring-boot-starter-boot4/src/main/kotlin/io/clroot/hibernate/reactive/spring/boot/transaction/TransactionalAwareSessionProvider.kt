package io.clroot.hibernate.reactive.spring.boot.transaction

import io.clroot.hibernate.reactive.ReadOnlyTransactionException
import io.clroot.hibernate.reactive.currentContextOrNull
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.withContext
import org.hibernate.reactive.mutiny.Mutiny
import org.slf4j.LoggerFactory
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import kotlin.reflect.KProperty1

/**
 * @Transactional 컨텍스트를 인식하는 세션 제공자.
 *
 * 세션 획득 우선순위:
 * 1. Spring @Transactional 컨텍스트 (ReactorContext를 통해)
 * 2. ReactiveSessionContext (CoroutineContext를 통해, 기존 tx.transactional 방식)
 * 3. 새 세션 생성
 *
 * 이를 통해 @Transactional 어노테이션과 Repository를 함께 사용할 수 있습니다:
 * ```kotlin
 * @Service
 * class MyService(private val repository: MyRepository) {
 *     @Transactional
 *     suspend fun doSomething() {
 *         repository.save(entity)  // 자동으로 트랜잭션 세션 사용
 *     }
 * }
 * ```
 */
open class TransactionalAwareSessionProvider(
    private val sessionFactory: Mutiny.SessionFactory,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 읽기 전용 작업을 수행합니다.
     *
     * - @Transactional 컨텍스트에 세션이 있으면 재사용
     * - ReactiveSessionContext에 세션이 있으면 재사용
     * - 없으면 withSession으로 새 세션 생성
     */
    open suspend fun <T> read(block: (Mutiny.Session) -> Uni<T>): T {
        // 1. @Transactional 컨텍스트 확인
        val transactionalContext = getTransactionalSessionContext()
        if (transactionalContext != null) {
            return withContext(transactionalContext.dispatcher ?: currentCoroutineContext()) {
                block(transactionalContext.session).awaitSuspending()
            }
        }

        // 2. ReactiveSessionContext 확인 (기존 tx.transactional 방식)
        val existingContext = currentContextOrNull()
        if (existingContext != null) {
            return block(existingContext.session).awaitSuspending()
        }

        // 3. 새 세션 생성
        return sessionFactory
            .withSession { session ->
                block(session)
            }.awaitSuspending()
    }

    /**
     * 쓰기 작업을 수행합니다.
     *
     * - @Transactional 컨텍스트에 세션이 있으면 재사용
     * - ReactiveSessionContext에 세션이 있으면 재사용
     * - 없으면 withTransaction으로 새 트랜잭션 생성
     *
     * @throws ReadOnlyTransactionException readOnly 컨텍스트 내에서 호출 시
     */
    open suspend fun <T> write(block: (Mutiny.Session) -> Uni<T>): T {
        // 1. @Transactional 컨텍스트 확인
        val transactionalContext = getTransactionalSessionContext()
        if (transactionalContext != null) {
            if (transactionalContext.isReadOnly) {
                throw ReadOnlyTransactionException(
                    "Cannot perform write operation in read-only @Transactional. " +
                            "Remove readOnly=true from @Transactional annotation.",
                )
            }
            return withContext(transactionalContext.dispatcher ?: currentCoroutineContext()) {
                block(transactionalContext.session).awaitSuspending()
            }
        }

        // 2. ReactiveSessionContext 확인 (기존 tx.transactional 방식)
        val existingContext = currentContextOrNull()
        return when {
            existingContext?.isReadOnly == true -> {
                throw ReadOnlyTransactionException(
                    "Cannot perform write operation in read-only transaction. " +
                            "Use tx.transactional {} instead of tx.readOnly {}",
                )
            }

            existingContext != null -> {
                block(existingContext.session).awaitSuspending()
            }

            else -> {
                // 3. 새 트랜잭션 생성
                sessionFactory
                    .withTransaction { session ->
                        block(session)
                    }.awaitSuspending()
            }
        }
    }

    /**
     * 현재 @Transactional 컨텍스트에서 세션을 가져옵니다.
     */
    private suspend fun getTransactionalSessionContext(): TransactionalSessionInfo? {
        // CoroutineContext에서 ReactorContext 가져오기
        val reactorContext = currentCoroutineContext()[ReactorContext]?.context
            ?: return null

        return try {
            TransactionSynchronizationManager.forCurrentTransaction()
                .mapNotNull { tsm ->
                    val holder = tsm.getResource(sessionFactory) as? MutinySessionHolder
                    holder?.let {
                        TransactionalSessionInfo(
                            session = it.getSession(),
                            isReadOnly = it.toReactiveSessionContext().isReadOnly,
                            dispatcher = it.getDispatcher(),
                        )
                    }
                }
                .contextWrite { it.putAll(reactorContext) }
                .block() // ReactorContext 내에서 동기적으로 조회
        } catch (e: Exception) {
            log.debug("Failed to get transactional session context, which may be expected if none exists.", e)
            null
        }
    }

    private data class TransactionalSessionInfo(
        val session: Mutiny.Session,
        val isReadOnly: Boolean,
        val dispatcher: kotlinx.coroutines.CoroutineDispatcher?,
    )

    // ==================== Lazy Loading 편의 메서드 ====================

    /**
     * 엔티티의 Lazy 연관관계를 fetch합니다.
     *
     * FETCH JOIN을 사용하기 어려운 경우(동적 조건, 다중 컬렉션 등)의 대안입니다.
     * 가능하면 FETCH JOIN을 먼저 고려하세요.
     *
     * 사용 예시:
     * ```kotlin
     * @Transactional(readOnly = true)
     * suspend fun getParentWithChildren(id: Long): ParentEntity {
     *     val parent = parentRepository.findById(id)!!
     *     sessionProvider.fetch(parent, ParentEntity::children)
     *     return parent
     * }
     * ```
     *
     * @param entity fetch할 연관관계를 가진 엔티티
     * @param property fetch할 연관관계 프로퍼티
     * @return fetch된 연관관계 컬렉션/엔티티
     */
    open suspend fun <E : Any, T> fetch(entity: E, property: KProperty1<E, T>): T {
        return read { session ->
            val association = property.get(entity)
            session.fetch(association)
        }
    }

    /**
     * 엔티티의 여러 Lazy 연관관계를 순차적으로 fetch합니다.
     *
     * 사용 예시:
     * ```kotlin
     * @Transactional(readOnly = true)
     * suspend fun getOrderWithDetails(id: Long): Order {
     *     val order = orderRepository.findById(id)!!
     *     sessionProvider.fetchAll(
     *         order,
     *         Order::items,
     *         Order::payments,
     *         Order::shippingInfo,
     *     )
     *     return order
     * }
     * ```
     *
     * @param entity fetch할 연관관계들을 가진 엔티티
     * @param properties fetch할 연관관계 프로퍼티들
     */
    open suspend fun <E : Any> fetchAll(entity: E, vararg properties: KProperty1<E, *>) {
        read { session ->
            var chain: Uni<*> = Uni.createFrom().voidItem()
            for (property in properties) {
                val association = property.get(entity)
                chain = chain.chain { _ -> session.fetch(association) }
            }
            chain
        }
    }

    /**
     * 엔티티를 현재 세션에 연결(merge)한 후 Lazy 연관관계를 fetch합니다.
     *
     * 다른 트랜잭션에서 조회한 detached 엔티티의 연관관계를 로딩할 때 사용합니다.
     *
     * 사용 예시:
     * ```kotlin
     * suspend fun processParent(detachedParent: ParentEntity) {
     *     // detachedParent는 다른 트랜잭션에서 조회됨
     *     val children = sessionProvider.fetchFromDetached(
     *         detachedParent,
     *         ParentEntity::class.java,
     *         ParentEntity::children,
     *     )
     *     // children 사용
     * }
     * ```
     *
     * @param entity detached 상태의 엔티티
     * @param entityClass 엔티티 클래스
     * @param property fetch할 연관관계 프로퍼티
     * @return fetch된 연관관계 컬렉션/엔티티
     */
    open suspend fun <E : Any, T> fetchFromDetached(
        entity: E,
        entityClass: Class<E>,
        property: KProperty1<E, T>,
    ): T {
        return read { session ->
            session.merge(entity)
                .chain { managedEntity ->
                    val association = property.get(managedEntity)
                    session.fetch(association)
                }
        }
    }
}
