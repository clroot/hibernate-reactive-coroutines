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
 * JPA 스펙 호환성 테스트용 서비스.
 *
 * @Transactional 어노테이션을 통해 트랜잭션 경계를 정의합니다.
 */
@Service
class JpaSpecTestService(
    private val testEntityRepository: TestEntityRepository,
    private val versionedEntityRepository: VersionedEntityRepository,
    private val parentEntityRepository: ParentEntityRepository,
    private val sessionFactory: Mutiny.SessionFactory,
    private val sessionProvider: TransactionalAwareSessionProvider,
) {

    // ==================== Dirty Checking 테스트 ====================

    @Transactional
    suspend fun saveEntity(name: String, value: Int): TestEntity {
        return testEntityRepository.save(TestEntity(name = name, value = value))
    }

    @Transactional(readOnly = true)
    suspend fun findById(id: Long): TestEntity? {
        return testEntityRepository.findById(id)
    }

    /**
     * 엔티티를 수정하지만 save()를 호출하지 않습니다.
     * Dirty Checking이 작동하면 트랜잭션 커밋 시 자동으로 flush됩니다.
     */
    @Transactional
    suspend fun modifyEntityWithoutSave(id: Long, newName: String, newValue: Int) {
        val entity = testEntityRepository.findById(id)
            ?: throw IllegalArgumentException("Entity not found: $id")

        // 명시적 save 없이 필드만 수정
        entity.name = newName
        entity.value = newValue
        // save() 호출 없음 - Dirty Checking으로 자동 반영되어야 함
    }

    @Transactional
    suspend fun modifyMultipleFields(id: Long, newName: String, newValue: Int) {
        val entity = testEntityRepository.findById(id)!!
        entity.name = newName
        entity.value = newValue
    }

    /**
     * readOnly 트랜잭션에서 엔티티 수정 시도.
     * 변경사항이 DB에 반영되지 않아야 합니다.
     */
    @Transactional(readOnly = true)
    suspend fun modifyInReadOnlyTransaction(id: Long, newName: String, newValue: Int) {
        val entity = testEntityRepository.findById(id)!!
        entity.name = newName
        entity.value = newValue
    }

    /**
     * 엔티티 수정 후 예외를 발생시켜 롤백을 유도합니다.
     */
    @Transactional
    suspend fun modifyAndThrowException(id: Long, newName: String, newValue: Int) {
        val entity = testEntityRepository.findById(id)!!
        entity.name = newName
        entity.value = newValue
        throw RuntimeException("Intentional exception for rollback test")
    }

    // ==================== First-level Cache 테스트 ====================

    /**
     * 같은 트랜잭션 내에서 두 번 조회하여 동일 인스턴스인지 확인합니다.
     */
    @Transactional(readOnly = true)
    suspend fun verifyFirstLevelCache(id: Long) {
        val entity1 = testEntityRepository.findById(id)
        val entity2 = testEntityRepository.findById(id)

        // 같은 트랜잭션 내에서는 동일 인스턴스여야 함
        require(entity1 === entity2) {
            "First-level cache not working: different instances returned for same ID"
        }
    }

    // ==================== Optimistic Locking 테스트 ====================

    @Transactional
    suspend fun saveVersionedEntity(name: String, value: Int): VersionedEntity {
        return versionedEntityRepository.save(VersionedEntity(name = name, value = value))
    }

    @Transactional
    suspend fun updateVersionedEntity(id: Long, newName: String, newValue: Int): VersionedEntity {
        val entity = versionedEntityRepository.findById(id)!!
        entity.name = newName
        entity.value = newValue
        return versionedEntityRepository.save(entity)
    }

    /**
     * 동시에 같은 엔티티를 수정하여 OptimisticLockException을 유발합니다.
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

    // ==================== Lazy Loading 테스트 ====================

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
     * 트랜잭션 내에서 Lazy 컬렉션에 접근합니다.
     *
     * Vert.x EventLoop 디스패처에서 실행되면 동기적 Lazy Loading도 작동하는지 테스트합니다.
     */
    @Transactional(readOnly = true)
    suspend fun getChildCountInTransaction(parentId: Long): Int {
        val parent = parentEntityRepository.findById(parentId)!!

        // Vert.x 디스패처에서 실행하여 동기적 접근 시도
        return sessionProvider.read { session ->
            // 먼저 세션에서 parent를 다시 조회하여 영속 상태로 만듦
            session.find(ParentEntity::class.java, parentId)
                .chain { managedParent ->
                    // 같은 세션에서 fetch
                    session.fetch(managedParent.children)
                }
                .map { children -> children.size }
        }
    }

    /**
     * JOIN FETCH를 사용하여 자식을 Eager 로딩합니다.
     */
    @Transactional(readOnly = true)
    suspend fun findParentWithChildrenEager(parentId: Long): ParentEntity? {
        return parentEntityRepository.findByIdWithChildren(parentId)
    }

    /**
     * sessionProvider.fetch()를 사용하여 Lazy 연관관계를 로딩합니다.
     *
     * FETCH JOIN이 어려운 경우의 대안입니다.
     */
    @Transactional(readOnly = true)
    suspend fun getChildrenUsingFetch(parentId: Long): List<ChildEntity> {
        val parent = parentEntityRepository.findById(parentId)!!
        return sessionProvider.fetch(parent, ParentEntity::children)
    }

    /**
     * sessionProvider.fetchAll()을 사용하여 여러 Lazy 연관관계를 한 번에 로딩합니다.
     */
    @Transactional(readOnly = true)
    suspend fun getParentWithAllAssociations(parentId: Long): ParentEntity {
        val parent = parentEntityRepository.findById(parentId)!!
        sessionProvider.fetchAll(parent, ParentEntity::children)
        return parent
    }

    /**
     * sessionProvider.fetchFromDetached()를 사용하여 detached 엔티티의 연관관계를 로딩합니다.
     */
    suspend fun getChildrenFromDetachedParent(detachedParent: ParentEntity): List<ChildEntity> {
        return sessionProvider.fetchFromDetached(
            detachedParent,
            ParentEntity::class.java,
            ParentEntity::children,
        )
    }

    // ==================== Flush 동작 테스트 ====================

    /**
     * flush가 쿼리 전에 발생하는지 검증합니다.
     */
    @Transactional
    suspend fun verifyFlushBeforeQuery() {
        // 1. 새 엔티티 저장 (아직 flush 안 됨)
        val uniqueValue = System.currentTimeMillis().toInt()
        val entity = testEntityRepository.save(TestEntity(name = "flush-test", value = uniqueValue))

        // 2. 같은 트랜잭션에서 쿼리 실행
        // JPA 스펙: 쿼리 전에 자동 flush되어 결과에 포함되어야 함
        val found = testEntityRepository.findAllByValue(uniqueValue)

        require(found.isNotEmpty()) {
            "Auto-flush before query not working: saved entity not found in query result"
        }
        require(found.any { it.id == entity.id }) {
            "Auto-flush before query not working: saved entity ID not in query result"
        }
    }
}
