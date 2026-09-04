package io.clroot.hibernate.reactive.test.isolated.pkg1

import io.clroot.hibernate.reactive.test.entity.TestEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * Test repository in pkg1 for basePackages scanning tests.
 */
interface Package1Repository : CoroutineCrudRepository<TestEntity, Long> {
    suspend fun findByName(name: String): TestEntity?
}
