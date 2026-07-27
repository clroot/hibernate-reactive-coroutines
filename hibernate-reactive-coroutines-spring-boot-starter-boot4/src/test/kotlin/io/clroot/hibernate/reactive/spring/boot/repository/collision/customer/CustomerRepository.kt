package io.clroot.hibernate.reactive.spring.boot.repository.collision.customer

import org.springframework.data.repository.kotlin.CoroutineCrudRepository

class Customer

interface CustomerRepository : CoroutineCrudRepository<Customer, Long>
