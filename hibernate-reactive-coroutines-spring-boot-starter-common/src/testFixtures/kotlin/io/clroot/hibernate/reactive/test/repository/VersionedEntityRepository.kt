package io.clroot.hibernate.reactive.test.repository

import io.clroot.hibernate.reactive.test.entity.VersionedEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * Repository for optimistic locking tests.
 */
interface VersionedEntityRepository : CoroutineCrudRepository<VersionedEntity, Long>
