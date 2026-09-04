package io.clroot.examples.ktor

import io.clroot.hibernate.reactive.ktor.HibernateReactive
import io.clroot.hibernate.reactive.ktor.hibernateRepository
import io.clroot.hibernate.reactive.ktor.hibernateTransactionExecutor
import io.clroot.hibernate.reactive.repository.CoroutineCrudRepository
import io.ktor.http.ContentType
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "ktor_smoke_records")
class SmokeRecord(
    var value: String = "",
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)

interface SmokeRecordRepository : CoroutineCrudRepository<SmokeRecord, Long>

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
            repository<SmokeRecordRepository, SmokeRecord, Long>()
        }

        val records = hibernateRepository<SmokeRecordRepository>()
        val transactions = hibernateTransactionExecutor
        routing {
            get("/smoke") {
                val count = transactions.transactional {
                    records.save(SmokeRecord(value = "ktor"))
                    records.count()
                }
                call.respondText("ktor-ok:$count", ContentType.Text.Plain)
            }
        }
    }.start(wait = true)
}
