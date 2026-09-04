package io.clroot.hibernate.reactive.test.repository.inheritance

import io.clroot.hibernate.reactive.test.entity.TestEntity

/**
 * Test repository that inherits from [BaseRepository].
 *
 * It exposes inherited methods alongside [findAllByValue].
 */
interface InheritedTestEntityRepository : BaseRepository<TestEntity, Long> {

    /**
     * Finds entities by value.
     */
    suspend fun findAllByValue(value: Int): List<TestEntity>
}
