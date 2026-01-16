package io.clroot.hibernate.reactive.test.repository

import io.clroot.hibernate.reactive.test.entity.AuditableEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface AuditableEntityRepository : CoroutineCrudRepository<AuditableEntity, Long>
