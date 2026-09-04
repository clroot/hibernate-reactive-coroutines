package io.clroot.hibernate.reactive.test.entity

import jakarta.persistence.*

/**
 * Entity for tests.
 */
@Entity
@Table(name = "test_entity")
class TestEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    var name: String,

    var value: Int = 0,
)
