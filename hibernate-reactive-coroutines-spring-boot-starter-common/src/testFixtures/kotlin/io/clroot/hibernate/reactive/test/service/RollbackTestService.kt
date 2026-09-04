package io.clroot.hibernate.reactive.test.service

import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.IOException

/**
 * Exercises transaction rollback rules, including nested calls through the Spring proxy.
 *
 * Spring AOP does not intercept self-invocation, so nested transaction scenarios call through
 * this self-reference.
 */
@Service
class RollbackTestService(
    private val testEntityRepository: TestEntityRepository,
) {
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private lateinit var self: RollbackTestService

    // Default rollback behavior for RuntimeException.

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

    // Checked exceptions do not roll back by default.

    @Transactional
    @Throws(IOException::class)
    suspend fun saveAndThrowCheckedException(name: String): TestEntity {
        val entity = testEntityRepository.save(TestEntity(name = name, value = 3))
        throw IOException("Checked exception - should NOT rollback by default")
    }

    // Explicit rollback rules for checked exceptions.
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

    // Explicit no-rollback rules for runtime exceptions.
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

    // Rollback propagation across nested proxy calls.

    @Transactional
    suspend fun outerSaveAndCallInnerThatFails(outerName: String, innerName: String): Pair<Long, Long> {
        val outer = testEntityRepository.save(TestEntity(name = outerName, value = 100))
        // Calling through self applies transactional AOP.
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
            // Calling through self applies transactional AOP.
            self.innerSaveAndFail(innerName)
        } catch (e: RuntimeException) {
            // The transaction is already marked rollback-only.
        }
        return outer
    }

    // Successful transactions do not roll back.

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

    @Transactional(readOnly = true)
    suspend fun findById(id: Long): TestEntity? {
        return testEntityRepository.findById(id)
    }

    @Transactional(readOnly = true)
    suspend fun findByName(name: String): TestEntity? {
        return testEntityRepository.findByName(name)
    }
}

class CustomCheckedException(message: String) : Exception(message)

class CustomRuntimeException(message: String) : RuntimeException(message)
