package io.clroot.examples.springboot

import io.clroot.hibernate.reactive.spring.boot.auditing.AuditingEntityListener
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import java.time.Instant

@Entity(name = "DemoTeam")
@Table(name = "spring_demo_teams")
@EntityListeners(AuditingEntityListener::class)
class DemoTeam(
    @Column(nullable = false, unique = true)
    var name: String = "",
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @OneToMany(mappedBy = "team", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    val members: MutableList<DemoMember> = mutableListOf(),
    @CreatedDate
    var createdAt: Instant? = null,
    @LastModifiedDate
    var updatedAt: Instant? = null,
    @CreatedBy
    var createdBy: String? = null,
    @LastModifiedBy
    var updatedBy: String? = null,
) {
    fun addMember(name: String, active: Boolean) {
        members += DemoMember(name = name, active = active, team = this)
    }
}

@Entity(name = "DemoMember")
@Table(name = "spring_demo_members")
class DemoMember(
    @Column(nullable = false)
    var name: String = "",
    @Column(nullable = false)
    var active: Boolean = true,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    var team: DemoTeam? = null,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)
