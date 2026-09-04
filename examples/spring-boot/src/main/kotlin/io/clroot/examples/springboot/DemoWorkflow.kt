package io.clroot.examples.springboot

import io.clroot.hibernate.reactive.spring.boot.auditing.ReactiveAuditingHandler
import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@Service
class DemoTransactions(
    private val teams: DemoTeamRepository,
    private val members: DemoMemberRepository,
    private val sessions: TransactionalAwareSessionProvider,
    private val auditing: ReactiveAuditingHandler<*>,
) {
    @Transactional
    suspend fun seed(): Long {
        members.deleteAll()
        teams.deleteAll()

        val team = DemoTeam(name = "platform").apply {
            addMember("alice", active = true)
            addMember("bob", active = true)
            addMember("carol", active = false)
        }
        val saved = teams.save(team)
        check(auditing.hasAuditorAware())
        check(saved.createdBy == "demo-user")
        check(saved.createdAt != null)
        return checkNotNull(saved.id)
    }

    @Transactional(readOnly = true)
    suspend fun summarize(teamId: Long): String {
        val team = checkNotNull(teams.findById(teamId))
        val loadedMembers = sessions.fetch(team, DemoTeam::members)
        val fetchJoinedTeam = checkNotNull(teams.findByIdWithMembers(teamId))
        val activeMembers = members.findByActive(
            active = true,
            pageable = PageRequest.of(0, 1, Sort.by("name").ascending()),
        )

        check(loadedMembers.size == 3)
        check(fetchJoinedTeam.members.size == 3)
        check(activeMembers.totalElements == 2L)
        check(activeMembers.hasNext())

        return listOf(
            "spring-demo-ok",
            team.name,
            loadedMembers.size,
            activeMembers.content.single().name,
            activeMembers.totalElements,
            activeMembers.hasNext(),
            team.createdBy,
            team.createdAt != null,
        ).joinToString(":")
    }
}

@RestController
class DemoController(
    private val transactions: DemoTransactions,
) {
    @PostMapping("/demo")
    suspend fun demo(): String = transactions.summarize(transactions.seed())
}
