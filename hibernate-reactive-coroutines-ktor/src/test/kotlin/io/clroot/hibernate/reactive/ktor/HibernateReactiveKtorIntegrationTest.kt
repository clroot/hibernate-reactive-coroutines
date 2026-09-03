package io.clroot.hibernate.reactive.ktor

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.repository.CoroutineCrudRepository
import io.clroot.hibernate.reactive.repository.auditing.CreatedBy
import io.clroot.hibernate.reactive.repository.auditing.LastModifiedBy
import io.clroot.hibernate.reactive.repository.auditing.ReactiveAuditorAware
import io.clroot.hibernate.reactive.repository.query.Param
import io.clroot.hibernate.reactive.repository.query.Query
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import jakarta.data.Order
import jakarta.data.Sort
import jakarta.data.page.Page
import jakarta.data.page.PageRequest
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant

class HibernateReactiveKtorIntegrationTest : DescribeSpec({
    val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).apply {
        withDatabaseName("ktor_test")
        withUsername("test")
        withPassword("test")
    }

    beforeSpec { postgres.start() }
    afterSpec { postgres.stop() }

    describe("HibernateReactive Ktor plugin") {
        it("starts, serves repositories and explicit transactions, then closes owned resources") {
            lateinit var installedResources: HibernateReactiveResources
            var currentAuditor = "system"

            testApplication {
                application {
                    install(HibernateReactive) {
                        database {
                            url = postgres.jdbcUrl
                            username = postgres.username
                            password = postgres.password
                            schemaGeneration = "create-drop"
                            poolSize = 2
                        }
                        dependencyInjection = true
                        auditorAware = ReactiveAuditorAware { currentAuditor }
                        repository<KtorUserRepository, KtorUser, Long>()
                    }

                    installedResources = hibernateReactive
                    val repository: KtorUserRepository by dependencies
                    val tx: ReactiveTransactionExecutor by dependencies
                    routing {
                        post("/exercise") {
                            repository.save(KtorUser(name = "alice", active = true))
                            tx.transactional {
                                repository.save(KtorUser(name = "bob", active = true))
                                repository.save(KtorUser(name = "carol", active = false))
                            }

                            val named = tx.readOnly { repository.findNamed("bob") }
                            val active = tx.readOnly {
                                repository.findByActive(
                                    true,
                                    PageRequest.ofPage(1, 1, true),
                                    Sort.asc("name"),
                                )
                            }
                            val descending = tx.readOnly {
                                repository.findAll(Order.by(Sort.desc("name")))
                            }

                            call.respondText(
                                listOf(
                                    named.single().name,
                                    active.content().single().name,
                                    active.totalElements().toString(),
                                    active.hasNext().toString(),
                                    descending.joinToString(",") { user -> user.name },
                                ).joinToString("|"),
                            )
                        }

                        post("/crud") {
                            currentAuditor = "creator"
                            val saved = repository.save(KtorUser(name = "dave", active = true))
                            val createdAt = saved.createdAt
                            val found = repository.findById(saved.id!!)
                            found!!.name = "dave-updated"
                            found.updatedAt = Instant.EPOCH
                            currentAuditor = "modifier"
                            val updated = repository.save(found)
                            repository.deleteById(updated.id!!)
                            call.respondText(
                                listOf(
                                    updated.name,
                                    repository.existsById(updated.id!!).toString(),
                                    updated.createdBy,
                                    updated.updatedBy,
                                    (createdAt != null && updated.updatedAt != Instant.EPOCH).toString(),
                                    (createdAt == updated.createdAt).toString(),
                                ).joinToString("|"),
                            )
                        }

                        post("/rollback") {
                            runCatching {
                                tx.transactional {
                                    repository.save(KtorUser(name = "rolled-back", active = true))
                                    error("rollback")
                                }
                            }
                            call.respondText(tx.readOnly { repository.count() }.toString())
                        }

                        get("/count") {
                            call.respondText(
                                hibernateSessionProvider.read { session ->
                                    session.createQuery(
                                        "SELECT COUNT(e) FROM KtorUser e",
                                        Long::class.java,
                                    ).singleResult
                                }.toString(),
                            )
                        }
                    }
                }

                client.post("/exercise").bodyAsText() shouldBe "bob|alice|2|true|carol,bob,alice"
                client.post("/crud").bodyAsText() shouldBe
                    "dave-updated|false|creator|modifier|true|true"
                client.post("/rollback").bodyAsText() shouldBe "3"
                client.get("/count").bodyAsText() shouldBe "3"
                installedResources.sessionFactory.isOpen shouldBe true
            }

            installedResources.sessionFactory.isOpen shouldBe false
            (installedResources.vertx as io.vertx.core.internal.VertxInternal)
                .closeFuture()
                .isClosed shouldBe true
        }
    }
})

@Entity(name = "KtorUser")
@Table(name = "ktor_users")
private class KtorUser(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(nullable = false)
    var name: String = "",
    @Column(nullable = false)
    var active: Boolean = true,
    @CreationTimestamp
    var createdAt: Instant? = null,
    @UpdateTimestamp
    var updatedAt: Instant? = null,
    @CreatedBy
    var createdBy: String? = null,
    @LastModifiedBy
    var updatedBy: String? = null,
)

private interface KtorUserRepository : CoroutineCrudRepository<KtorUser, Long> {
    suspend fun findByActive(
        active: Boolean,
        pageRequest: PageRequest,
        sort: Sort<KtorUser>,
    ): Page<KtorUser>

    @Query("FROM KtorUser e WHERE e.name = :name")
    suspend fun findNamed(@Param("name") name: String): List<KtorUser>
}
