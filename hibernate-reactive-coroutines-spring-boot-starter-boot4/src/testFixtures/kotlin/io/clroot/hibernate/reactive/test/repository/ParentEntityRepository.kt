package io.clroot.hibernate.reactive.test.repository

import io.clroot.hibernate.reactive.spring.boot.repository.query.Query
import io.clroot.hibernate.reactive.test.entity.ParentEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * Lazy Loading 테스트용 Repository.
 */
interface ParentEntityRepository : CoroutineCrudRepository<ParentEntity, Long> {

    /**
     * JOIN FETCH를 통한 Eager loading.
     */
    @Query("SELECT p FROM ParentEntity p LEFT JOIN FETCH p.children WHERE p.id = :id")
    suspend fun findByIdWithChildren(id: Long): ParentEntity?
}
