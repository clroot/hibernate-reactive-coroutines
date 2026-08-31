package io.clroot.hibernate.reactive.test.entity

import jakarta.persistence.*

/**
 * Lazy Loading 테스트용 부모 엔티티.
 *
 * @OneToMany 관계를 통해 Lazy Loading 동작을 테스트합니다.
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
