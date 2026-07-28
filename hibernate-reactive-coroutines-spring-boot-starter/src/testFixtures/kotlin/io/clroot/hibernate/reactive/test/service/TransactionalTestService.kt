package io.clroot.hibernate.reactive.test.service

import io.clroot.hibernate.reactive.ReactiveSessionContext
import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.currentSessionOrNull
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

    // === @Transactional 안에서 tx.transactional {} 을 함께 쓰는 경우 ===

    /**
     * `tx.transactional {}`이 바깥 `@Transactional`에 참여해야 합니다.
     * 별도 트랜잭션이 열리면 이 저장은 롤백되지 않습니다.
     */
    @Transactional
    suspend fun saveNestedAndFail(name: String, value: Int) {
        transactionExecutor.transactional {
            testEntityRepository.save(TestEntity(name = name, value = value))
        }
        throw RuntimeException("의도적 롤백 - 중첩 저장까지 롤백되어야 함")
    }

    /**
     * `tx.transactional {}`이 바깥 Spring 트랜잭션을 감지했는지 확인합니다.
     *
     * 감지하지 못하면 새 세션을 열면서 [ReactiveSessionContext]를 코루틴 컨텍스트에 추가합니다.
     * 즉 여기서 세션이 보인다면 쓰이지도 않을 세션이 하나 더 열렸다는 뜻입니다.
     */
    @Transactional
    suspend fun opensRedundantSession(): Boolean =
        transactionExecutor.transactional {
            currentSessionOrNull() != null
        }

    /**
     * 읽기 전용 `@Transactional` 안에서는 쓰기 트랜잭션으로 승격할 수 없습니다.
     */
    @Transactional(readOnly = true)
    suspend fun upgradeReadOnlyTransaction(name: String) {
        transactionExecutor.transactional {
            testEntityRepository.save(TestEntity(name = name, value = 0))
        }
    }
}
