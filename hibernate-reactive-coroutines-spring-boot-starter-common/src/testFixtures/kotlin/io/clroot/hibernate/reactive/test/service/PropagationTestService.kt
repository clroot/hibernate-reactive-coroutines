package io.clroot.hibernate.reactive.test.service

import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Service for transaction propagation tests.
 */
@Service
class PropagationTestService(
    private val testEntityRepository: TestEntityRepository,
    private val sessionProvider: TransactionalAwareSessionProvider,
) {

    // REQUIRED propagation (default)

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

    // REQUIRES_NEW propagation (unsupported; expected to fail)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    suspend fun requiresNewTransaction(name: String): TestEntity {
        return testEntityRepository.save(TestEntity(name = "requires-new-$name", value = 10))
    }

    // SUPPORTS propagation

    @Transactional(propagation = Propagation.SUPPORTS)
    suspend fun supportsWithTransaction(name: String): TestEntity {
        return testEntityRepository.save(TestEntity(name = "supports-$name", value = 20))
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    suspend fun supportsReadOnly(id: Long): TestEntity? {
        return testEntityRepository.findById(id)
    }

    // NOT_SUPPORTED propagation

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    suspend fun notSupportedAction(name: String): TestEntity {
        return testEntityRepository.save(TestEntity(name = "not-supported-$name", value = 30))
    }

    // MANDATORY propagation

    @Transactional(propagation = Propagation.MANDATORY)
    suspend fun mandatoryAction(name: String): TestEntity {
        return testEntityRepository.save(TestEntity(name = "mandatory-$name", value = 40))
    }

    // NEVER propagation

    @Transactional(propagation = Propagation.NEVER)
    suspend fun neverAction(name: String): TestEntity {
        return testEntityRepository.save(TestEntity(name = "never-$name", value = 50))
    }

    // Write attempt in a read-only transaction

    @Transactional(readOnly = true)
    suspend fun readOnlyWriteAttempt(name: String): TestEntity {
        return testEntityRepository.save(TestEntity(name = "readonly-write-$name", value = 60))
    }

    // Nested transaction scenarios

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

    // Timeouts

    @Transactional(timeout = 1) // One-second timeout.
    suspend fun transactionWithShortTimeout(name: String, delayMillis: Long): TestEntity {
        val entity = testEntityRepository.save(TestEntity(name = name, value = 70))
        kotlinx.coroutines.delay(delayMillis)
        return entity
    }

    @Transactional(timeout = 10) // Ten-second timeout.
    suspend fun transactionWithLongTimeout(name: String): TestEntity {
        return testEntityRepository.save(TestEntity(name = name, value = 71))
    }

    @Transactional(timeout = 1)
    suspend fun transactionWithSlowQuery(name: String): TestEntity {
        val entity = testEntityRepository.save(TestEntity(name = name, value = 74))
        sessionProvider.read { session ->
            session
                .createNativeQuery("SELECT 1 FROM pg_sleep(5)", Int::class.javaObjectType)
                .resultList
                .replaceWith(Unit)
        }
        return entity
    }

    @Transactional(readOnly = true)
    suspend fun currentStatementTimeout(): String =
        sessionProvider.read { session ->
            session
                .createNativeQuery("SHOW statement_timeout", String::class.java)
                .singleResult
        }

    @Transactional(timeout = 1)
    suspend fun repositoryCallAfterTimeout(name: String, delayMillis: Long): TestEntity {
        kotlinx.coroutines.delay(delayMillis)
        return testEntityRepository.save(TestEntity(name = name, value = 72))
    }

    @Transactional(timeout = 1)
    suspend fun catchRepositoryTimeout(name: String, delayMillis: Long) {
        kotlinx.coroutines.delay(delayMillis)
        try {
            testEntityRepository.save(TestEntity(name = name, value = 73))
        } catch (_: org.springframework.transaction.TransactionTimedOutException) {
            // The transaction must remain rollback-only even if application code catches the operation error.
        }
    }

    // Transaction isolation

    @Transactional(isolation = Isolation.READ_COMMITTED)
    suspend fun isolationReadCommitted(name: String): Pair<TestEntity, String> {
        val entity = testEntityRepository.save(TestEntity(name = "isolation-rc-$name", value = 80))
        return entity to currentIsolationLevel()
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    suspend fun isolationRepeatableRead(name: String): Pair<TestEntity, String> {
        val entity = testEntityRepository.save(TestEntity(name = "isolation-rr-$name", value = 81))
        return entity to currentIsolationLevel()
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    suspend fun isolationSerializable(name: String): Pair<TestEntity, String> {
        val entity = testEntityRepository.save(TestEntity(name = "isolation-s-$name", value = 82))
        return entity to currentIsolationLevel()
    }

    private suspend fun currentIsolationLevel(): String =
        sessionProvider.read { session ->
            session
                .createNativeQuery("show transaction_isolation", String::class.java)
                .singleResult
        }

    // Helpers

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
