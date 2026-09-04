package io.clroot.hibernate.reactive.test.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * Entity whose HQL name differs from its simple class name.
 *
 * Inferring the entity name from the class name causes all queries targeting this entity to fail.
 */
@Entity(name = "RenamedAlias")
@Table(name = "renamed_entity")
class RenamedEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    var name: String = "",
)
