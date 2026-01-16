package io.clroot.hibernate.reactive.test.repository

import io.clroot.hibernate.reactive.test.entity.AnotherEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 추가 테스트용 Repository
 */
interface AnotherEntityRepository : CoroutineCrudRepository<AnotherEntity, Long>
