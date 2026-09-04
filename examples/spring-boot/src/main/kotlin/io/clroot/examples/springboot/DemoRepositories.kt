package io.clroot.examples.springboot

import io.clroot.hibernate.reactive.repository.query.Param
import io.clroot.hibernate.reactive.repository.query.Query
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface DemoTeamRepository : CoroutineCrudRepository<DemoTeam, Long> {
    @Query("SELECT DISTINCT t FROM DemoTeam t LEFT JOIN FETCH t.members WHERE t.id = :id")
    suspend fun findByIdWithMembers(@Param("id") id: Long): DemoTeam?
}

interface DemoMemberRepository : CoroutineCrudRepository<DemoMember, Long> {
    suspend fun findByActive(active: Boolean, pageable: Pageable): Page<DemoMember>
}
