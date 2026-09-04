package io.clroot.hibernate.reactive.test.entity

import jakarta.persistence.*

@Entity
@Table(name = "another_entity")
class AnotherEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    var description: String,
)
