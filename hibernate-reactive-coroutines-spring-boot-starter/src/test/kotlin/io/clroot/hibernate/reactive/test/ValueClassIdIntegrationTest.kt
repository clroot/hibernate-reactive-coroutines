package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.test.entity.ValueEntityId
import io.clroot.hibernate.reactive.test.entity.ValueIdEntity
import io.clroot.hibernate.reactive.test.repository.ValueIdEntityRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.concurrent.atomic.AtomicLong

@SpringBootTest(classes = [TestApplication::class])
class ValueClassIdIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var repository: ValueIdEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    private val identifiers = AtomicLong(10_000)

    init {
        describe("Kotlin value class repository identifiers") {
            it("finds an entity by its wrapped identifier") {
                val id = ValueEntityId(identifiers.incrementAndGet())
                tx.transactional {
                    repository.save(ValueIdEntity(id, "value-id"))
                }

                val found = tx.readOnly {
                    repository.findById(id)
                }

                found?.id shouldBe id
                found?.name shouldBe "value-id"
                repository.existsById(id) shouldBe true
            }

            it("finds entities by wrapped identifier collections") {
                val ids = listOf(
                    ValueEntityId(identifiers.incrementAndGet()),
                    ValueEntityId(identifiers.incrementAndGet()),
                )
                tx.transactional {
                    ids.forEach { repository.save(ValueIdEntity(it, "value-${it.value}")) }
                }

                val found = repository.findAllById(ids).toList()

                found.map { it.id }.toSet() shouldBe ids.toSet()
            }

            it("deletes by wrapped single and collection identifiers") {
                val first = ValueEntityId(identifiers.incrementAndGet())
                val second = ValueEntityId(identifiers.incrementAndGet())
                tx.transactional {
                    repository.save(ValueIdEntity(first, "delete-single"))
                    repository.save(ValueIdEntity(second, "delete-collection"))
                }

                repository.deleteById(first)
                repository.existsById(first) shouldBe false

                repository.deleteAllById(listOf(second))
                repository.existsById(second) shouldBe false
            }
        }
    }
}
