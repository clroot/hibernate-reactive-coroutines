package io.clroot.hibernate.reactive.test.service

import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import io.clroot.hibernate.reactive.test.entity.ChildEntity
import io.clroot.hibernate.reactive.test.entity.ParentEntity
import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.entity.VersionedEntity
import io.clroot.hibernate.reactive.test.repository.ParentEntityRepository
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.clroot.hibernate.reactive.test.repository.VersionedEntityRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.hibernate.reactive.mutiny.Mutiny
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for JPA specification compatibility tests.
 *
 * `@Transactional` defines the transaction boundary.
 */
@Service
class JpaSpecTestService(
    private val testEntityRepository: TestEntityRepository,
    private val versionedEntityRepository: VersionedEntityRepository,
    private val parentEntityRepository: ParentEntityRepository,
    private val sessionFactory: Mutiny.SessionFactory,
    private val sessionProvider: TransactionalAwareSessionProvider,
) {

    // Dirty checking

    @Transactional
    suspend fun saveEntity(name: String, value: Int): TestEntity {
        return testEntityRepository.save(TestEntity(name = name, value = value))
    }

    @Transactional(readOnly = true)
    suspend fun findById(id: Long): TestEntity? {
        return testEntityRepository.findById(id)
    }

    /**
     * Modifies a managed entity without calling `save()`.
     *
     * Dirty checking should flush the changes when the transaction commits.
     */
    @Transactional
    suspend fun modifyEntityWithoutSave(id: Long, newName: String, newValue: Int) {
        val entity = testEntityRepository.findById(id)
            ?: throw IllegalArgumentException("Entity not found: $id")

        entity.name = newName
        entity.value = newValue
    }

    @Transactional
    suspend fun modifyMultipleFields(id: Long, newName: String, newValue: Int) {
        val entity = testEntityRepository.findById(id)!!
        entity.name = newName
        entity.value = newValue
    }

    /**
     * Attempts to modify an entity in a read-only transaction.
     *
     * The changes must not be persisted.
     */
    @Transactional(readOnly = true)
    suspend fun modifyInReadOnlyTransaction(id: Long, newName: String, newValue: Int) {
        val entity = testEntityRepository.findById(id)!!
        entity.name = newName
        entity.value = newValue
    }

    /**
     * Modifies an entity, then throws an exception to trigger rollback.
     */
    @Transactional
    suspend fun modifyAndThrowException(id: Long, newName: String, newValue: Int) {
        val entity = testEntityRepository.findById(id)!!
        entity.name = newName
        entity.value = newValue
        throw RuntimeException("Intentional exception for rollback test")
    }

    // First-level cache

    /**
     * Verifies that repeated lookups in one transaction return the same instance.
     */
    @Transactional(readOnly = true)
    suspend fun verifyFirstLevelCache(id: Long) {
        val entity1 = testEntityRepository.findById(id)
        val entity2 = testEntityRepository.findById(id)

        require(entity1 === entity2) {
            "First-level cache not working: different instances returned for same ID"
        }
    }

    // Optimistic locking

    @Transactional
    suspend fun saveVersionedEntity(name: String, value: Int): VersionedEntity {
        return versionedEntityRepository.save(VersionedEntity(name = name, value = value))
    }

    @Transactional
    suspend fun saveVersionedEntity(entity: VersionedEntity): VersionedEntity {
        return versionedEntityRepository.save(entity)
    }

    @Transactional
    suspend fun updateVersionedEntity(id: Long, newName: String, newValue: Int): VersionedEntity {
        val entity = versionedEntityRepository.findById(id)!!
        entity.name = newName
        entity.value = newValue
        return versionedEntityRepository.save(entity)
    }

    /**
     * Updates the same entity concurrently to trigger an optimistic lock exception.
     */
    suspend fun concurrentUpdate(id: Long) {
        coroutineScope {
            val job1 = async {
                updateVersionedEntityWithDelay(id, "update1", 1, delayMs = 100)
            }
            val job2 = async {
                updateVersionedEntityWithDelay(id, "update2", 2, delayMs = 50)
            }
            awaitAll(job1, job2)
        }
    }

    @Transactional
    suspend fun updateVersionedEntityWithDelay(
        id: Long,
        newName: String,
        newValue: Int,
        delayMs: Long,
    ): VersionedEntity {
        val entity = versionedEntityRepository.findById(id)!!
        delay(delayMs)
        entity.name = newName
        entity.value = newValue
        return versionedEntityRepository.save(entity)
    }

    // Lazy loading

    @Transactional
    suspend fun saveParentWithChildren(parentName: String, childNames: List<String>): Long {
        val parent = ParentEntity(name = parentName)
        childNames.forEach { childName ->
            parent.addChild(ChildEntity(name = childName))
        }
        val saved = parentEntityRepository.save(parent)
        return saved.id!!
    }

    /**
     * Accesses a lazy collection within a transaction.
     *
     * Verifies synchronous lazy loading on the Vert.x event-loop dispatcher.
     */
    @Transactional(readOnly = true)
    suspend fun getChildCountInTransaction(parentId: Long): Int {
        val parent = parentEntityRepository.findById(parentId)!!

        return sessionProvider.read { session ->
            // Re-fetch the parent in this session so it is managed.
            session.find(ParentEntity::class.java, parentId)
                .chain { managedParent ->
                    session.fetch(managedParent.children)
                }
                .map { children -> children.size }
        }
    }

    /**
     * Eagerly loads children with a fetch join.
     */
    @Transactional(readOnly = true)
    suspend fun findParentWithChildrenEager(parentId: Long): ParentEntity? {
        return parentEntityRepository.findByIdWithChildren(parentId)
    }

    /**
     * Loads a lazy association with `sessionProvider.fetch()`.
     *
     * This is an alternative when a fetch join is unsuitable.
     */
    @Transactional(readOnly = true)
    suspend fun getChildrenUsingFetch(parentId: Long): List<ChildEntity> {
        val parent = parentEntityRepository.findById(parentId)!!
        return sessionProvider.fetch(parent, ParentEntity::children)
    }

    /**
     * Loads all requested lazy associations with `sessionProvider.fetchAll()`.
     */
    @Transactional(readOnly = true)
    suspend fun getParentWithAllAssociations(parentId: Long): ParentEntity {
        val parent = parentEntityRepository.findById(parentId)!!
        sessionProvider.fetchAll(parent, ParentEntity::children)
        return parent
    }

    /**
     * Loads an association from a detached entity with `sessionProvider.fetchFromDetached()`.
     */
    suspend fun getChildrenFromDetachedParent(detachedParent: ParentEntity): List<ChildEntity> {
        return sessionProvider.fetchFromDetached(
            detachedParent,
            ParentEntity::class.java,
            ParentEntity::children,
        )
    }

    // Flush behavior

    /**
     * Verifies that changes are flushed before a query.
     */
    @Transactional
    suspend fun verifyFlushBeforeQuery() {
        val uniqueValue = System.currentTimeMillis().toInt()
        val entity = testEntityRepository.save(TestEntity(name = "flush-test", value = uniqueValue))

        // JPA requires automatic flushing before the query so this entity is included.
        val found = testEntityRepository.findAllByValue(uniqueValue)

        require(found.isNotEmpty()) {
            "Auto-flush before query not working: saved entity not found in query result"
        }
        require(found.any { it.id == entity.id }) {
            "Auto-flush before query not working: saved entity ID not in query result"
        }
    }
}
