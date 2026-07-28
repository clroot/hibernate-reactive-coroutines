package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.spring.boot.auditing.ReactiveAuditingHandler
import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.hibernate.reactive.mutiny.Mutiny

/**
 * 기본 CRUD 작업을 담당하는 내부 헬퍼 클래스.
 *
 * CoroutineCrudRepository의 기본 메서드(save, find, delete, count 등)를 구현합니다.
 *
 * @param T 엔티티 타입
 * @param ID 엔티티의 ID 타입
 */
internal class CrudOperations<T : Any, ID : Any>(
    private val entityClass: Class<T>,
    private val entityName: String,
    private val sessionProvider: TransactionalAwareSessionProvider,
    private val transactionExecutor: ReactiveTransactionExecutor,
    private val auditingHandler: ReactiveAuditingHandler<*>?,
) {

    private suspend fun prepareEntity(entity: T): Boolean {
        val isNew = EntityStateDetector.isNew(entity)
        if (auditingHandler != null) {
            if (isNew) {
                auditingHandler.markCreated(entity)
            } else {
                auditingHandler.markModified(entity)
            }
        }
        return isNew
    }

    private fun save(
        session: Mutiny.Session,
        entity: T,
        isNew: Boolean,
    ): Uni<T> =
        if (isNew) {
            session.persist(entity).replaceWith(entity)
        } else {
            session.merge(entity)
        }

    // ============================================
    // Save 작업
    // ============================================

    suspend fun save(entity: T): T {
        val isNew = prepareEntity(entity)

        return sessionProvider.write { session -> save(session, entity, isNew) }
    }

    fun saveAll(entities: Iterable<T>): Flow<T> = flow {
        val entityList = entities.toList()
        if (entityList.isEmpty()) return@flow

        val preparedEntities = entityList.map { entity ->
            entity to prepareEntity(entity)
        }
        val savedEntities = sessionProvider.write { session ->
            Multi.createFrom()
                .iterable(preparedEntities)
                .onItem()
                .transformToUniAndConcatenate { (entity, isNew) ->
                    save(session, entity, isNew)
                }
                .collect()
                .asList()
        }
        emitAll(savedEntities.asFlow())
    }

    fun saveAllFlow(entities: Flow<T>): Flow<T> = flow {
        val list = entities.toList()
        emitAll(saveAll(list))
    }

    // ============================================
    // Find 작업
    // ============================================

    suspend fun findById(id: ID): T? = sessionProvider.read { session ->
        session.find(entityClass, RepositoryIdAdapter.unwrap(id))
    }

    fun findAll(): Flow<T> = flow {
        val list = sessionProvider.read { session ->
            session.createQuery("FROM $entityName", entityClass).resultList
        }
        emitAll(list.asFlow())
    }

    fun findAllById(ids: Iterable<ID>): Flow<T> = flow {
        val idList = ids.map(RepositoryIdAdapter::unwrap)
        if (idList.isEmpty()) return@flow

        val list = sessionProvider.read { session ->
            session.createQuery("FROM $entityName e WHERE e.id IN :ids", entityClass)
                .setParameter("ids", idList)
                .resultList
        }
        emitAll(list.asFlow())
    }

    fun findAllByIdFlow(ids: Flow<ID>): Flow<T> = flow {
        val idList = ids.toList()
        emitAll(findAllById(idList))
    }

    // ============================================
    // Exists / Count 작업
    // ============================================

    suspend fun existsById(id: ID): Boolean {
        val count = sessionProvider.read { session ->
            session.createQuery("SELECT COUNT(e) FROM $entityName e WHERE e.id = :id", Long::class.javaObjectType)
                .setParameter("id", RepositoryIdAdapter.unwrap(id))
                .singleResult
        }
        return (count ?: 0L) > 0
    }

    suspend fun count(): Long = sessionProvider.read { session ->
        session.createQuery("SELECT COUNT(e) FROM $entityName e", Long::class.java)
            .singleResult
    }

    // ============================================
    // Delete 작업
    // ============================================

    /**
     * 엔티티를 로드한 뒤 제거합니다.
     *
     * bulk `DELETE` 문은 cascade, `@Version` 낙관적 락, 영속성 컨텍스트 정리를 모두 건너뛰므로
     * Spring Data JPA와 동일하게 로드 후 제거하는 방식을 사용합니다.
     * 대상이 없으면 조용히 무시합니다.
     */
    suspend fun deleteById(id: ID) {
        sessionProvider.write<Unit> { session ->
            session.find(entityClass, RepositoryIdAdapter.unwrap(id))
                .onItem()
                .transformToUni { entity: T? -> removeIfPresent(session, entity) }
                .replaceWith(Unit)
        }
    }

    suspend fun delete(entity: T) {
        sessionProvider.write<Unit> { session ->
            session.merge(entity)
                .chain { merged -> session.remove(merged).replaceWith(Unit) }
        }
    }

    suspend fun deleteAllById(ids: Iterable<ID>) {
        val idList = ids.map(RepositoryIdAdapter::unwrap)
        if (idList.isEmpty()) return

        sessionProvider.write<Unit> { session ->
            session.createQuery("FROM $entityName e WHERE e.id IN :ids", entityClass)
                .setParameter("ids", idList)
                .resultList
                .chain { entities -> removeAll(session, entities) }
                .replaceWith(Unit)
        }
    }

    suspend fun deleteAllEntities(entities: Iterable<T>) {
        val entityList = entities.toList()
        if (entityList.isEmpty()) return

        transactionExecutor.transactional {
            entityList.forEach { delete(it) }
        }
    }

    suspend fun deleteAllFlow(entities: Flow<T>) {
        deleteAllEntities(entities.toList())
    }

    suspend fun deleteAll() {
        sessionProvider.write<Unit> { session ->
            session.createQuery("FROM $entityName e", entityClass)
                .resultList
                .chain { entities -> removeAll(session, entities) }
                .replaceWith(Unit)
        }
    }

    private fun removeIfPresent(session: Mutiny.Session, entity: T?): Uni<Void> =
        if (entity == null) Uni.createFrom().voidItem() else session.remove(entity)

    private fun removeAll(session: Mutiny.Session, entities: List<T>): Uni<Void> =
        if (entities.isEmpty()) {
            Uni.createFrom().voidItem()
        } else {
            val managed: List<Any> = entities
            session.removeAll(*managed.toTypedArray())
        }
}
