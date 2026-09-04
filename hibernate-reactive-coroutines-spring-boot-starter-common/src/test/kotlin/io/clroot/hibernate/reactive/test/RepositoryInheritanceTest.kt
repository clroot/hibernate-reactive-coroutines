package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.inheritance.InheritedTestEntityRepository
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(classes = [TestApplication::class])
class RepositoryInheritanceTest : IntegrationTestBase() {

    @Autowired
    private lateinit var inheritedRepository: InheritedTestEntityRepository

    init {
        describe("generic type preservation") {
            it("infers the entity type from the inherited repository") {
                val entity = TestEntity(name = "generic-type-test", value = 100)

                val saved = inheritedRepository.save(entity)

                saved.shouldNotBeNull()
                saved.id.shouldNotBeNull()
                saved.name shouldBe "generic-type-test"
            }

            it("supports the generic ID type") {
                val entity = inheritedRepository.save(TestEntity(name = "id-type-test", value = 200))

                val found = inheritedRepository.findById(entity.id!!)

                found.shouldNotBeNull()
                found.id shouldBe entity.id
            }
        }

        describe("inherited repository methods") {
            it("supports findByName defined by BaseRepository") {
                inheritedRepository.save(TestEntity(name = "inherited-method-test", value = 300))

                val found = inheritedRepository.findByName("inherited-method-test")

                found.shouldNotBeNull()
                found.name shouldBe "inherited-method-test"
            }

            it("supports existsByName defined by BaseRepository") {
                inheritedRepository.save(TestEntity(name = "exists-test", value = 400))

                val exists = inheritedRepository.existsByName("exists-test")
                val notExists = inheritedRepository.existsByName("not-exists")

                exists shouldBe true
                notExists shouldBe false
            }
        }

        describe("methods defined by the child repository") {
            it("supports findAllByValue defined by InheritedTestEntityRepository") {
                inheritedRepository.save(TestEntity(name = "value-test-1", value = 500))
                inheritedRepository.save(TestEntity(name = "value-test-2", value = 500))
                inheritedRepository.save(TestEntity(name = "value-test-3", value = 600))

                val found = inheritedRepository.findAllByValue(500)

                found.size shouldBe 2
                found.all { it.value == 500 } shouldBe true
            }
        }

        describe("inherited CRUD methods") {
            it("supports CoroutineCrudRepository count") {
                val initialCount = inheritedRepository.count()
                inheritedRepository.save(TestEntity(name = "count-test", value = 700))

                val newCount = inheritedRepository.count()

                newCount shouldBe initialCount + 1
            }

            it("supports CoroutineCrudRepository deleteById") {
                val entity = inheritedRepository.save(TestEntity(name = "delete-test", value = 800))
                val id = entity.id!!

                inheritedRepository.deleteById(id)

                inheritedRepository.findById(id).shouldBeNull()
            }
        }
    }
}
