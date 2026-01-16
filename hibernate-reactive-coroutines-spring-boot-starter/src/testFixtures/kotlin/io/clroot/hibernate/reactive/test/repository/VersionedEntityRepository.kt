package io.clroot.hibernate.reactive.test.repository

import io.clroot.hibernate.reactive.test.entity.VersionedEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * Optimistic Locking 테스트용 Repository.
 */
interface VersionedEntityRepository : CoroutineCrudRepository<VersionedEntity, Long>
