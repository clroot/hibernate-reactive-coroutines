package io.clroot.hibernate.reactive.test.repository

import io.clroot.hibernate.reactive.repository.query.Query
import io.clroot.hibernate.reactive.test.entity.ParentEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/** Repository for lazy-loading tests. */
interface ParentEntityRepository : CoroutineCrudRepository<ParentEntity, Long> {

    /** Eagerly loads children with `JOIN FETCH`. */
    @Query("SELECT p FROM ParentEntity p LEFT JOIN FETCH p.children WHERE p.id = :id")
    suspend fun findByIdWithChildren(id: Long): ParentEntity?
}
