package io.clroot.hibernate.reactive.spring.boot.repository.collision.alpha

import org.springframework.data.repository.kotlin.CoroutineCrudRepository

class AlphaOrder

interface OrderRepository : CoroutineCrudRepository<AlphaOrder, Long>
