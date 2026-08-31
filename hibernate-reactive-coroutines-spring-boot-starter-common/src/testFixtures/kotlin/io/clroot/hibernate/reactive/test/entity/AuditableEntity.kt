package io.clroot.hibernate.reactive.test.entity

import io.clroot.hibernate.reactive.spring.boot.auditing.AuditingEntityListener
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import java.time.LocalDateTime

/**
 * Auditing 기능 테스트용 엔티티
 */
@Entity
@Table(name = "auditable_entity")
@EntityListeners(AuditingEntityListener::class)
class AuditableEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    var name: String,

    @CreatedDate
    var createdAt: LocalDateTime? = null,

    @CreatedBy
    var createdBy: String? = null,

    @LastModifiedDate
    var updatedAt: LocalDateTime? = null,

    @LastModifiedBy
    var updatedBy: String? = null,
)
