package io.clroot.hibernate.reactive.test.repository

import io.clroot.hibernate.reactive.test.entity.RenamedEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/** Repository for an entity renamed with `@Entity(name = ...)`. */
interface RenamedEntityRepository : CoroutineCrudRepository<RenamedEntity, Long> {

    suspend fun findByName(name: String): RenamedEntity?

    suspend fun deleteByName(name: String): Long
}
