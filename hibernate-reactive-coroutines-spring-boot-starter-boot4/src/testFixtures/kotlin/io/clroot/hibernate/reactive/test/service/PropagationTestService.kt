package io.clroot.hibernate.reactive.test.service

import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 트랜잭션 전파 옵션 테스트를 위한 서비스 클래스.
 */
@Service
class PropagationTestService(
    private val testEntityRepository: TestEntityRepository,
) {

    // ============================================
    // REQUIRED (기본) 전파 테스트
    // ============================================

    @Transactional
    suspend fun outerRequired(name: String, innerAction: suspend () -> Unit): TestEntity {
        val entity = testEntityRepository.save(TestEntity(name = "outer-$name", value = 1))
        innerAction()
        return entity
    }

    @Transactional(propagation = Propagation.REQUIRED)
    suspend fun innerRequired(name: String): TestEntity {
        return testEntityRepository.save(TestEntity(name = "inner-$name", value = 2))
    }

    @Transactional(propagation = Propagation.REQUIRED)
    suspend fun innerRequiredWithException(name: String): TestEntity {
        testEntityRepository.save(TestEntity(name = "inner-fail-$name", value = 3))
        throw RuntimeException("Inner REQUIRED exception")
    }

    // ============================================
    // REQUIRES_NEW 전파 테스트 (미지원 - 에러 예상)
    // ============================================

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    suspend fun requiresNewTransaction(name: String): TestEntity {
        return testEntityRepository.save(TestEntity(name = "requires-new-$name", value = 10))
    }

    // ============================================
    // SUPPORTS 전파 테스트
    // ============================================

    @Transactional(propagation = Propagation.SUPPORTS)
    suspend fun supportsWithTransaction(name: String): TestEntity {
        return testEntityRepository.save(TestEntity(name = "supports-$name", value = 20))
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    suspend fun supportsReadOnly(id: Long): TestEntity? {
        return testEntityRepository.findById(id)
    }

    // ============================================
    // NOT_SUPPORTED 전파 테스트
    // ============================================

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    suspend fun notSupportedAction(name: String): TestEntity {
        return testEntityRepository.save(TestEntity(name = "not-supported-$name", value = 30))
    }

    // ============================================
    // MANDATORY 전파 테스트
    // ============================================

    @Transactional(propagation = Propagation.MANDATORY)
    suspend fun mandatoryAction(name: String): TestEntity {
        return testEntityRepository.save(TestEntity(name = "mandatory-$name", value = 40))
    }

    // ============================================
    // NEVER 전파 테스트
    // ============================================

    @Transactional(propagation = Propagation.NEVER)
    suspend fun neverAction(name: String): TestEntity {
        return testEntityRepository.save(TestEntity(name = "never-$name", value = 50))
    }

    // ============================================
    // readOnly 쓰기 시도 테스트
    // ============================================

    @Transactional(readOnly = true)
    suspend fun readOnlyWriteAttempt(name: String): TestEntity {
        return testEntityRepository.save(TestEntity(name = "readonly-write-$name", value = 60))
    }

    // ============================================
    // 중첩 트랜잭션 시나리오
    // ============================================

    @Transactional
    suspend fun nestedRequiredBothCommit(outerName: String, innerName: String): Pair<TestEntity, TestEntity> {
        val outer = testEntityRepository.save(TestEntity(name = outerName, value = 100))
        val inner = innerRequired(innerName)
        return outer to inner
    }

    @Transactional
    suspend fun nestedRequiredInnerFails(outerName: String, innerName: String): Pair<TestEntity, TestEntity> {
        val outer = testEntityRepository.save(TestEntity(name = outerName, value = 100))
        val inner = innerRequiredWithException(innerName)
        return outer to inner
    }

    // ============================================
    // timeout 테스트
    // ============================================

    @Transactional(timeout = 1) // 1초 타임아웃
    suspend fun transactionWithShortTimeout(name: String, delayMillis: Long): TestEntity {
        val entity = testEntityRepository.save(TestEntity(name = name, value = 70))
        // 지연 시뮬레이션
        kotlinx.coroutines.delay(delayMillis)
        return entity
    }

    @Transactional(timeout = 10) // 10초 타임아웃
    suspend fun transactionWithLongTimeout(name: String): TestEntity {
        return testEntityRepository.save(TestEntity(name = name, value = 71))
    }

    // ============================================
    // isolation 테스트
    // ============================================

    @Transactional(isolation = Isolation.READ_COMMITTED)
    suspend fun isolationReadCommitted(name: String): TestEntity {
        return testEntityRepository.save(TestEntity(name = "isolation-rc-$name", value = 80))
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    suspend fun isolationRepeatableRead(name: String): TestEntity {
        return testEntityRepository.save(TestEntity(name = "isolation-rr-$name", value = 81))
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    suspend fun isolationSerializable(name: String): TestEntity {
        return testEntityRepository.save(TestEntity(name = "isolation-s-$name", value = 82))
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

    @Transactional
    suspend fun saveEntity(name: String, value: Int): TestEntity {
        return testEntityRepository.save(TestEntity(name = name, value = value))
    }
}
