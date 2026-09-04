package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.test.entity.RenamedEntity
import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.RenamedEntityRepository
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Verifies entity-name resolution and LIKE wildcard escaping against a real database.
 */
@SpringBootTest
class EntityNamingAndLikeEscapingTest : IntegrationTestBase() {

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    @Autowired
    private lateinit var renamedEntityRepository: RenamedEntityRepository

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    init {
        describe("an entity renamed with @Entity(name = ...)") {

            it("supports basic CRUD operations") {
                val saved = tx.transactional {
                    renamedEntityRepository.save(RenamedEntity(name = "renamed"))
                }

                tx.readOnly {
                    renamedEntityRepository.findById(saved.id!!).shouldNotBeNull().name shouldBe "renamed"
                    renamedEntityRepository.count() shouldBe 1L
                }
            }

            it("supports derived queries") {
                tx.transactional {
                    renamedEntityRepository.save(RenamedEntity(name = "target"))
                    renamedEntityRepository.save(RenamedEntity(name = "other"))
                }

                tx.readOnly {
                    renamedEntityRepository.findByName("target").shouldNotBeNull()
                }

                val deleted = tx.transactional { renamedEntityRepository.deleteByName("target") }

                deleted shouldBe 1L
                tx.readOnly { renamedEntityRepository.count() } shouldBe 1L
            }
        }

        describe("LIKE wildcard escaping") {

            it("does not match every row when Containing receives a percent sign") {
                tx.transactional {
                    testEntityRepository.save(TestEntity(name = "plain", value = 1))
                    testEntityRepository.save(TestEntity(name = "100% cotton", value = 2))
                }

                val matched = tx.readOnly { testEntityRepository.findAllByNameContaining("%") }

                matched.map { it.name } shouldContainExactlyInAnyOrder listOf("100% cotton")
            }

            it("matches an underscore in Containing literally rather than as a single-character wildcard") {
                tx.transactional {
                    testEntityRepository.save(TestEntity(name = "a_b", value = 1))
                    testEntityRepository.save(TestEntity(name = "axb", value = 2))
                }

                val matched = tx.readOnly { testEntityRepository.findAllByNameContaining("a_b") }

                matched.map { it.name } shouldContainExactlyInAnyOrder listOf("a_b")
            }
        }
    }
}
