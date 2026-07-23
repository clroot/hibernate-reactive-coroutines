package io.clroot.hibernate.reactive.test.repository

import io.clroot.hibernate.reactive.test.entity.ValueEntityId
import io.clroot.hibernate.reactive.test.entity.ValueIdEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface ValueIdEntityRepository : CoroutineCrudRepository<ValueIdEntity, ValueEntityId>
