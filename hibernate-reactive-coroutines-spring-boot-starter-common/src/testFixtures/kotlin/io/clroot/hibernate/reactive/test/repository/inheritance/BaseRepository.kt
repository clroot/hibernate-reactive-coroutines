package io.clroot.hibernate.reactive.test.repository.inheritance

import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.NoRepositoryBean

/**
 * Base repository interface for inheritance tests.
 *
 * `@NoRepositoryBean` prevents it from being registered as a bean directly.
 */
@NoRepositoryBean
interface BaseRepository<T : Any, ID : Any> : CoroutineCrudRepository<T, ID> {

    /**
     * Finds an entity by name. Inherited by child repositories.
     */
    suspend fun findByName(name: String): T?

    /**
     * Checks whether an entity exists by name. Inherited by child repositories.
     */
    suspend fun existsByName(name: String): Boolean
}
