package io.clroot.hibernate.reactive.test.repository

import io.clroot.hibernate.reactive.test.entity.RenamedEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * `@Entity(name = ...)`로 이름을 바꾼 엔티티용 Repository.
 */
interface RenamedEntityRepository : CoroutineCrudRepository<RenamedEntity, Long> {

    suspend fun findByName(name: String): RenamedEntity?

    suspend fun deleteByName(name: String): Long
}
