package io.clroot.hibernate.reactive.test.entity

import jakarta.persistence.CascadeType
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table

/**
 * Parent entity for lazy-loading tests.
 *
 * Tests lazy-loading behavior through a {@code @OneToMany} relationship.
 */
@Entity
@Table(name = "parent_entity")
class ParentEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    var name: String,

    @OneToMany(mappedBy = "parent", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    val children: MutableList<ChildEntity> = mutableListOf(),
) {
    fun addChild(child: ChildEntity) {
        children.add(child)
        child.parent = this
    }
}
