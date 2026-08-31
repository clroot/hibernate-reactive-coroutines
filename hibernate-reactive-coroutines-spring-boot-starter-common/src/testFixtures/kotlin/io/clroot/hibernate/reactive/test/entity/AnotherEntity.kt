package io.clroot.hibernate.reactive.test.entity

import jakarta.persistence.*

/**
 * 추가 테스트용 엔티티
 */
@Entity
@Table(name = "another_entity")
class AnotherEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    var description: String,
)
