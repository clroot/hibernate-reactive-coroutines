package io.clroot.hibernate.reactive.spring.boot.repository.collision.beta

import org.springframework.data.repository.kotlin.CoroutineCrudRepository

class BetaOrder

interface OrderRepository : CoroutineCrudRepository<BetaOrder, Long>
