package io.clroot.examples.ktor

import io.clroot.hibernate.reactive.repository.CoroutineCrudRepository
import io.clroot.hibernate.reactive.repository.query.Param
import io.clroot.hibernate.reactive.repository.query.Query
import jakarta.data.Sort
import jakarta.data.page.Page
import jakarta.data.page.PageRequest

interface KtorDemoTeamRepository : CoroutineCrudRepository<KtorDemoTeam, Long> {
    @Query("SELECT DISTINCT t FROM KtorDemoTeam t LEFT JOIN FETCH t.members WHERE t.id = :id")
    suspend fun findByIdWithMembers(@Param("id") id: Long): KtorDemoTeam?
}

interface KtorDemoMemberRepository : CoroutineCrudRepository<KtorDemoMember, Long> {
    suspend fun findByActive(
        active: Boolean,
        pageRequest: PageRequest,
        sort: Sort<KtorDemoMember>,
    ): Page<KtorDemoMember>
}
