package io.clroot.hibernate.reactive.test.entity

import jakarta.persistence.*

/**
 * Entity for optimistic-locking tests.
 *
 * Tests concurrent-update detection through the {@code @Version} field.
 */
@Entity
@Table(name = "versioned_entity")
class VersionedEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    var name: String,

    var value: Int = 0,

    @Version
    var version: Long? = null,
)
