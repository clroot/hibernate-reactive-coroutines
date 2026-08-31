package io.clroot.hibernate.reactive.test.entity

import jakarta.persistence.*

/**
 * Optimistic Locking 테스트용 엔티티.
 *
 * @Version 필드를 통해 동시 수정 감지를 테스트합니다.
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
