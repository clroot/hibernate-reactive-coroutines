package io.clroot.hibernate.reactive.test.entity

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version

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
