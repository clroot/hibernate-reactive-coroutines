package io.clroot.hibernate.reactive.test.service

import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * @Transactional 테스트를 위한 서비스 클래스.
 *
 * Repository를 직접 사용하며, @Transactional이 세션을 자동으로 관리합니다.
 * 순수 suspend 함수로 구현되어 Mono 없이 깔끔하게 사용할 수 있습니다.
 */
@Service
class TransactionalTestService(
    private val testEntityRepository: TestEntityRepository,
) {
    @Transactional
    suspend fun saveEntity(name: String, value: Int): TestEntity {
        val entity = TestEntity(name = name, value = value)
        return testEntityRepository.save(entity)
    }

    @Transactional(readOnly = true)
    suspend fun findById(id: Long): TestEntity? {
        return testEntityRepository.findById(id)
    }

    @Transactional(readOnly = true)
    suspend fun findByIdReadOnly(id: Long): TestEntity? {
        return testEntityRepository.findById(id)
    }

    @Transactional
    suspend fun saveAndFail(name: String, value: Int, onSaved: (Long) -> Unit): TestEntity {
        val entity = TestEntity(name = name, value = value)
        val saved = testEntityRepository.save(entity)
        onSaved(saved.id!!)
        throw RuntimeException("의도적 롤백")
    }

    /**
     * 여러 엔티티를 한 트랜잭션에서 저장.
     */
    @Transactional
    suspend fun saveMultipleEntities(names: List<String>): List<TestEntity> {
        return names.mapIndexed { index, name ->
            testEntityRepository.save(TestEntity(name = name, value = index))
        }
    }

    /**
     * 여러 엔티티를 저장한 후 예외 발생 - 모든 저장이 롤백되어야 함.
     */
    @Transactional
    suspend fun saveMultipleAndFail(names: List<String>, onSaved: (List<Long>) -> Unit): List<TestEntity> {
        val savedEntities = names.mapIndexed { index, name ->
            testEntityRepository.save(TestEntity(name = name, value = index))
        }
        onSaved(savedEntities.map { it.id!! })
        throw RuntimeException("의도적 롤백 - 모든 저장이 롤백되어야 함")
    }
}
