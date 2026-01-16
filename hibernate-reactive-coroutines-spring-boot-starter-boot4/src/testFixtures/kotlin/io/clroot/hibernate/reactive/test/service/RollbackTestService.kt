package io.clroot.hibernate.reactive.test.service

import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.IOException

/**
 * 트랜잭션 롤백 규칙 테스트를 위한 서비스 클래스.
 *
 * 중첩 트랜잭션 테스트를 위해 self 참조를 사용합니다.
 * Spring AOP는 self-invocation(같은 객체 내 메서드 호출)을 가로채지 못하므로,
 * 프록시를 통해 내부 메서드를 호출하기 위해 self 참조가 필요합니다.
 */
@Service
class RollbackTestService(
    private val testEntityRepository: TestEntityRepository,
) {
    // self 참조 - 프록시를 통한 내부 메서드 호출을 위해 필요
    // @Lazy로 순환 참조 문제 해결
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private lateinit var self: RollbackTestService

    // ============================================
    // 기본 롤백 동작 (RuntimeException)
    // ============================================

    @Transactional
    suspend fun saveAndThrowRuntimeException(name: String): TestEntity {
        val entity = testEntityRepository.save(TestEntity(name = name, value = 1))
        throw RuntimeException("Runtime exception for rollback")
    }

    @Transactional
    suspend fun saveAndThrowIllegalStateException(name: String): TestEntity {
        val entity = testEntityRepository.save(TestEntity(name = name, value = 2))
        throw IllegalStateException("IllegalStateException for rollback")
    }

    // ============================================
    // Checked Exception (기본적으로 롤백 안 함)
    // ============================================

    @Transactional
    @Throws(IOException::class)
    suspend fun saveAndThrowCheckedException(name: String): TestEntity {
        val entity = testEntityRepository.save(TestEntity(name = name, value = 3))
        throw IOException("Checked exception - should NOT rollback by default")
    }

    // ============================================
    // rollbackFor 지정
    // ============================================

    @Transactional(rollbackFor = [IOException::class])
    @Throws(IOException::class)
    suspend fun saveAndThrowCheckedWithRollbackFor(name: String): TestEntity {
        val entity = testEntityRepository.save(TestEntity(name = name, value = 4))
        throw IOException("Checked exception with rollbackFor - SHOULD rollback")
    }

    @Transactional(rollbackFor = [CustomCheckedException::class])
    @Throws(CustomCheckedException::class)
    suspend fun saveAndThrowCustomCheckedException(name: String): TestEntity {
        val entity = testEntityRepository.save(TestEntity(name = name, value = 5))
        throw CustomCheckedException("Custom checked exception - SHOULD rollback")
    }

    // ============================================
    // noRollbackFor 지정
    // ============================================

    @Transactional(noRollbackFor = [IllegalArgumentException::class])
    suspend fun saveAndThrowNoRollbackForException(name: String): TestEntity {
        val entity = testEntityRepository.save(TestEntity(name = name, value = 6))
        throw IllegalArgumentException("IllegalArgumentException with noRollbackFor - should NOT rollback")
    }

    @Transactional(noRollbackFor = [CustomRuntimeException::class])
    suspend fun saveAndThrowCustomNoRollbackException(name: String): TestEntity {
        val entity = testEntityRepository.save(TestEntity(name = name, value = 7))
        throw CustomRuntimeException("Custom runtime exception - should NOT rollback")
    }

    // ============================================
    // 중첩 호출에서의 롤백 전파
    // ============================================

    @Transactional
    suspend fun outerSaveAndCallInnerThatFails(outerName: String, innerName: String): Pair<Long, Long> {
        val outer = testEntityRepository.save(TestEntity(name = outerName, value = 100))
        // self를 통해 호출해야 프록시를 거쳐 @Transactional AOP가 적용됨
        val inner = self.innerSaveAndFail(innerName)
        return outer.id!! to inner.id!!
    }

    @Transactional
    suspend fun innerSaveAndFail(name: String): TestEntity {
        val entity = testEntityRepository.save(TestEntity(name = name, value = 101))
        throw RuntimeException("Inner transaction failure - outer should also rollback")
    }

    @Transactional
    suspend fun outerCatchesInnerException(outerName: String, innerName: String): TestEntity {
        val outer = testEntityRepository.save(TestEntity(name = outerName, value = 200))
        try {
            // self를 통해 호출해야 프록시를 거쳐 @Transactional AOP가 적용됨
            self.innerSaveAndFail(innerName)
        } catch (e: RuntimeException) {
            // 내부 예외를 잡아도 트랜잭션은 이미 rollback-only로 마킹됨
        }
        return outer
    }

    // ============================================
    // 성공 케이스 (롤백 없음)
    // ============================================

    @Transactional
    suspend fun saveSuccessfully(name: String, value: Int): TestEntity {
        return testEntityRepository.save(TestEntity(name = name, value = value))
    }

    @Transactional
    suspend fun saveMultipleSuccessfully(names: List<String>): List<TestEntity> {
        val savedEntities = mutableListOf<TestEntity>()
        for ((index, name) in names.withIndex()) {
            savedEntities.add(testEntityRepository.save(TestEntity(name = name, value = index)))
        }
        return savedEntities
    }

    // ============================================
    // 헬퍼 메서드
    // ============================================

    @Transactional(readOnly = true)
    suspend fun findById(id: Long): TestEntity? {
        return testEntityRepository.findById(id)
    }

    @Transactional(readOnly = true)
    suspend fun findByName(name: String): TestEntity? {
        return testEntityRepository.findByName(name)
    }
}

/**
 * 테스트용 커스텀 Checked Exception
 */
class CustomCheckedException(message: String) : Exception(message)

/**
 * 테스트용 커스텀 Runtime Exception
 */
class CustomRuntimeException(message: String) : RuntimeException(message)
