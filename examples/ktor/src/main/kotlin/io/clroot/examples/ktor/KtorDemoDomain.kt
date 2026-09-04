package io.clroot.examples.ktor

import io.clroot.hibernate.reactive.repository.auditing.CreatedBy
import io.clroot.hibernate.reactive.repository.auditing.LastModifiedBy
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

@Entity(name = "KtorDemoTeam")
@Table(name = "ktor_demo_teams")
class KtorDemoTeam(
    @Column(nullable = false, unique = true)
    var name: String = "",
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @OneToMany(mappedBy = "team", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    val members: MutableList<KtorDemoMember> = mutableListOf(),
    @CreationTimestamp
    var createdAt: Instant? = null,
    @UpdateTimestamp
    var updatedAt: Instant? = null,
    @CreatedBy
    var createdBy: String? = null,
    @LastModifiedBy
    var updatedBy: String? = null,
) {
    fun addMember(name: String, active: Boolean) {
        members += KtorDemoMember(name = name, active = active, team = this)
    }
}

@Entity(name = "KtorDemoMember")
@Table(name = "ktor_demo_members")
class KtorDemoMember(
    @Column(nullable = false)
    var name: String = "",
    @Column(nullable = false)
    var active: Boolean = true,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    var team: KtorDemoTeam? = null,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)
