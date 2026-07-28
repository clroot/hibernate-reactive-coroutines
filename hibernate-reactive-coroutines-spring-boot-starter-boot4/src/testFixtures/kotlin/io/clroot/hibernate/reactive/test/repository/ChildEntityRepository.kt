package io.clroot.hibernate.reactive.test.repository

import io.clroot.hibernate.reactive.test.entity.ChildEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * cascade 삭제 검증용 Repository.
 */
interface ChildEntityRepository : CoroutineCrudRepository<ChildEntity, Long>
