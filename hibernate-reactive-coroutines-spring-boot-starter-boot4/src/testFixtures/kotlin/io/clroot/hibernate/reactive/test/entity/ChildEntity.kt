package io.clroot.hibernate.reactive.test.entity

import jakarta.persistence.*

/**
 * Lazy Loading 테스트용 자식 엔티티.
 */
@Entity
@Table(name = "child_entity")
class ChildEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    var name: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    var parent: ParentEntity? = null,
)
