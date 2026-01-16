package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.spring.boot.auditing.AuditMetadata
import io.clroot.hibernate.reactive.spring.boot.auditing.ReactiveAuditingHandler
import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList

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
    private val sessionProvider: TransactionalAwareSessionProvider,
    private val transactionExecutor: ReactiveTransactionExecutor,
    private val auditingHandler: ReactiveAuditingHandler<*>?,
) {
    private val entityName: String = entityClass.simpleName

    // ============================================
    // Save 작업
    // ============================================

    suspend fun save(entity: T): T {
        if (auditingHandler != null) {
            val isNew = AuditMetadata.isNew(entity)
            if (isNew) {
                auditingHandler.markCreated(entity)
            } else {
                auditingHandler.markModified(entity)
            }
        }

        return sessionProvider.write { session ->
            session.merge(entity)
        }
    }

    fun saveAll(entities: Iterable<T>): Flow<T> = flow {
        val entityList = entities.toList()
        if (entityList.isEmpty()) return@flow

        val savedEntities = transactionExecutor.transactional {
            entityList.map { save(it) }
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
        session.find(entityClass, id)
    }

    fun findAll(): Flow<T> = flow {
        val list = sessionProvider.read { session ->
            session.createQuery("FROM $entityName", entityClass).resultList
        }
        emitAll(list.asFlow())
    }

    fun findAllById(ids: Iterable<ID>): Flow<T> = flow {
        val idList = ids.toList()
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
                .setParameter("id", id)
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

    suspend fun deleteById(id: ID) {
        sessionProvider.write<Unit> { session ->
            session.createMutationQuery("DELETE FROM $entityName e WHERE e.id = :id")
                .setParameter("id", id)
                .executeUpdate()
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
        val idList = ids.toList()
        if (idList.isEmpty()) return

        sessionProvider.write<Unit> { session ->
            session.createMutationQuery("DELETE FROM $entityName e WHERE e.id IN :ids")
                .setParameter("ids", idList)
                .executeUpdate()
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
            session.createMutationQuery("DELETE FROM $entityName")
                .executeUpdate()
                .replaceWith(Unit)
        }
    }
}
