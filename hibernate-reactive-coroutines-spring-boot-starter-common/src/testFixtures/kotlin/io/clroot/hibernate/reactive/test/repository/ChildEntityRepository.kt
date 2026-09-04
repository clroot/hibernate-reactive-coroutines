package io.clroot.hibernate.reactive.test.repository

import io.clroot.hibernate.reactive.test.entity.ChildEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/** Repository for cascade deletion tests. */
interface ChildEntityRepository : CoroutineCrudRepository<ChildEntity, Long>
