package io.clroot.examples.ktor

import io.clroot.hibernate.reactive.ktor.HibernateReactive
import io.clroot.hibernate.reactive.ktor.hibernateRepository
import io.clroot.hibernate.reactive.ktor.hibernateTransactionExecutor
import io.clroot.hibernate.reactive.repository.auditing.ReactiveAuditorAware
import io.ktor.http.ContentType
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import jakarta.data.Sort
import jakarta.data.page.PageRequest

fun main() {
    val host = System.getenv("DB_HOST") ?: "localhost"
    val port = System.getenv("DB_PORT") ?: "5432"
    val database = System.getenv("DB_NAME") ?: "hrc"
    val username = System.getenv("DB_USER") ?: "hrc"
    val password = System.getenv("DB_PASSWORD") ?: "hrc"

    embeddedServer(Netty, port = 8081) {
        install(HibernateReactive) {
            database {
                url = "postgresql://$host:$port/$database"
                this.username = username
                this.password = password
                schemaGeneration = "create-drop"
            }
            auditorAware = ReactiveAuditorAware { "demo-user" }
            repository<KtorDemoTeamRepository, KtorDemoTeam, Long>()
            repository<KtorDemoMemberRepository, KtorDemoMember, Long>()
        }

        val teams = hibernateRepository<KtorDemoTeamRepository>()
        val members = hibernateRepository<KtorDemoMemberRepository>()
        val transactions = hibernateTransactionExecutor
        routing {
            post("/demo") {
                val teamId = transactions.transactional {
                    members.deleteAll()
                    teams.deleteAll()

                    val team = KtorDemoTeam(name = "platform").apply {
                        addMember("alice", active = true)
                        addMember("bob", active = true)
                        addMember("carol", active = false)
                    }
                    val saved = teams.save(team)
                    check(saved.createdBy == "demo-user")
                    check(saved.createdAt != null)
                    checkNotNull(saved.id)
                }
                val summary = transactions.readOnly {
                    val team = checkNotNull(teams.findByIdWithMembers(teamId))
                    val activeMembers = members.findByActive(
                        active = true,
                        pageRequest = PageRequest.ofPage(1, 1, true),
                        sort = Sort.asc("name"),
                    )

                    check(team.members.size == 3)
                    check(activeMembers.totalElements() == 2L)
                    check(activeMembers.hasNext())

                    listOf(
                        "ktor-demo-ok",
                        team.name,
                        team.members.size,
                        activeMembers.content().single().name,
                        activeMembers.totalElements(),
                        activeMembers.hasNext(),
                        team.createdBy,
                        team.createdAt != null,
                    ).joinToString(":")
                }
                call.respondText(summary, ContentType.Text.Plain)
            }
        }
    }.start(wait = true)
}
