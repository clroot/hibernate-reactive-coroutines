package io.clroot.hibernate.reactive.test.isolated.pkg2

import io.clroot.hibernate.reactive.test.entity.TestEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * Test repository in pkg2 for basePackages scanning tests.
 */
interface Package2Repository : CoroutineCrudRepository<TestEntity, Long> {
    suspend fun findByValue(value: Int): List<TestEntity>
}
