package io.clroot.hibernate.reactive.test.service

import io.clroot.hibernate.reactive.ReactiveSessionContext
import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.currentSessionOrNull
import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Exercises `@Transactional` session management with suspend repository operations. */
@Service
class TransactionalTestService(
    private val testEntityRepository: TestEntityRepository,
    private val transactionExecutor: ReactiveTransactionExecutor,
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
        throw RuntimeException("Intentional rollback")
    }

    @Transactional
    suspend fun saveMultipleEntities(names: List<String>): List<TestEntity> {
        return names.mapIndexed { index, name ->
            testEntityRepository.save(TestEntity(name = name, value = index))
        }
    }

    /** Fails after saving multiple entities so all saves must roll back. */
    @Transactional
    suspend fun saveMultipleAndFail(names: List<String>, onSaved: (List<Long>) -> Unit): List<TestEntity> {
        val savedEntities = names.mapIndexed { index, name ->
            testEntityRepository.save(TestEntity(name = name, value = index))
        }
        onSaved(savedEntities.map { it.id!! })
        throw RuntimeException("Intentional rollback: all saves must roll back")
    }

    /**
     * `tx.transactional {}` must join the surrounding `@Transactional`.
     * A separate transaction would leave this save unrolled back.
     */
    @Transactional
    suspend fun saveNestedAndFail(name: String, value: Int) {
        transactionExecutor.transactional {
            testEntityRepository.save(TestEntity(name = name, value = value))
        }
        throw RuntimeException("Intentional rollback: nested save must roll back")
    }

    /**
     * Verifies that `tx.transactional {}` detects the surrounding Spring transaction.
     *
     * Otherwise it opens a new session and adds [ReactiveSessionContext] to the coroutine context.
     * A visible session here therefore indicates an unnecessary additional session.
     */
    @Transactional
    suspend fun opensRedundantSession(): Boolean =
        transactionExecutor.transactional {
            currentSessionOrNull() != null
        }

    /** A read-only `@Transactional` cannot be upgraded to a write transaction. */
    @Transactional(readOnly = true)
    suspend fun upgradeReadOnlyTransaction(name: String) {
        transactionExecutor.transactional {
            testEntityRepository.save(TestEntity(name = name, value = 0))
        }
    }
}
