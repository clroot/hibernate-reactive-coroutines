package io.clroot.hibernate.reactive.test.entity

import jakarta.persistence.*

/**
 * 테스트용 엔티티
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
