package io.clroot.examples.springboot

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootApplication
class SpringBootExample

fun main(args: Array<String>) {
    runApplication<SpringBootExample>(*args)
}

@Entity
@Table(name = "spring_smoke_records")
class SmokeRecord(
    var value: String = "",
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)

interface SmokeRecordRepository : CoroutineCrudRepository<SmokeRecord, Long>

@RestController
class SmokeController(
    private val records: SmokeRecordRepository,
) {
    @GetMapping("/smoke")
    @Transactional
    suspend fun smoke(): String {
        records.save(SmokeRecord(value = "spring-boot"))
        return "spring-boot-ok:${records.count()}"
    }
}
