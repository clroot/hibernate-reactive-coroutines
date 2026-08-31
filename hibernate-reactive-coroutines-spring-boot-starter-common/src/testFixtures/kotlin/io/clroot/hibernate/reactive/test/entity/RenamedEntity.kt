package io.clroot.hibernate.reactive.test.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * HQL 엔티티 이름이 클래스 단순 이름과 다른 엔티티.
 *
 * 엔티티 이름을 클래스 이름에서 유추하면 이 엔티티를 대상으로 한 모든 쿼리가 실패합니다.
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
