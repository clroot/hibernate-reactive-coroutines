package io.clroot.hibernate.reactive.test.repository

import io.clroot.hibernate.reactive.test.entity.AnotherEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/** Repository for additional test coverage. */
interface AnotherEntityRepository : CoroutineCrudRepository<AnotherEntity, Long>
