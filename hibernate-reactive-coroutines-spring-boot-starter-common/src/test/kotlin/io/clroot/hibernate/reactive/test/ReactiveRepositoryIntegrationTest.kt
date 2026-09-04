package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(classes = [TestApplication::class])
class ReactiveRepositoryIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("CoroutineCrudRepository") {
            context("save") {
                it("saves an entity and generates an ID") {
                    val entity = TestEntity(name = "saveTest", value = 100)

                    val saved = tx.transactional {
                        testEntityRepository.save(entity)
                    }

                    saved.id.shouldNotBeNull()
                    saved.name shouldBe "saveTest"
                    saved.value shouldBe 100
                }

                it("manages the supplied new entity instance and assigns its generated ID") {
                    val entity = TestEntity(name = "persistNew", value = 101)

                    val saved = tx.transactional {
                        testEntityRepository.save(entity)
                    }

                    saved shouldBe entity
                    entity.id.shouldNotBeNull()
                }

                it("persists the supplied new entity instances through saveAll") {
                    val entities = listOf(
                        TestEntity(name = "persistAll1", value = 102),
                        TestEntity(name = "persistAll2", value = 103),
                    )

                    val saved = testEntityRepository.saveAll(entities).toList()

                    saved shouldBe entities
                    entities.forEach { it.id.shouldNotBeNull() }
                }

                it("updates an existing entity") {
                    val entity = TestEntity(name = "updateTest", value = 50)
                    val saved = tx.transactional {
                        testEntityRepository.save(entity)
                    }

                    saved.name = "updated"
                    saved.value = 999

                    val updated = tx.transactional {
                        testEntityRepository.save(saved)
                    }

                    updated.id shouldBe saved.id
                    updated.name shouldBe "updated"
                    updated.value shouldBe 999
                }
            }

            context("findById") {
                it("finds an existing entity") {
                    val entity = TestEntity(name = "findByIdTest", value = 200)
                    val saved = tx.transactional {
                        testEntityRepository.save(entity)
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findById(saved.id!!)
                    }

                    found.shouldNotBeNull()
                    found.id shouldBe saved.id
                    found.name shouldBe "findByIdTest"
                }

                it("returns null for a nonexistent ID") {
                    val found = tx.readOnly {
                        testEntityRepository.findById(99999L)
                    }

                    found.shouldBeNull()
                }
            }

            context("findAll") {
                it("finds all entities") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "all1", value = 1))
                        testEntityRepository.save(TestEntity(name = "all2", value = 2))
                        testEntityRepository.save(TestEntity(name = "all3", value = 3))
                    }

                    val all = tx.readOnly {
                        testEntityRepository.findAll().toList()
                    }

                    all.size shouldBe 3
                }
            }

            context("count") {
                it("returns the entity count") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "count1", value = 1))
                        testEntityRepository.save(TestEntity(name = "count2", value = 2))
                    }

                    val count = tx.readOnly {
                        testEntityRepository.count()
                    }

                    count shouldBe 2
                }
            }

            context("existsById") {
                it("returns true for an existing ID") {
                    val saved = tx.transactional {
                        testEntityRepository.save(TestEntity(name = "exists", value = 1))
                    }

                    val exists = tx.readOnly {
                        testEntityRepository.existsById(saved.id!!)
                    }

                    exists shouldBe true
                }

                it("returns false for a nonexistent ID") {
                    val exists = tx.readOnly {
                        testEntityRepository.existsById(99999L)
                    }

                    exists shouldBe false
                }
            }

            context("deleteById") {
                it("deletes an entity by ID") {
                    val saved = tx.transactional {
                        testEntityRepository.save(TestEntity(name = "deleteById", value = 1))
                    }

                    tx.transactional {
                        testEntityRepository.deleteById(saved.id!!)
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findById(saved.id!!)
                    }
                    found.shouldBeNull()
                }

                it("ignores deletion of a nonexistent ID without throwing") {
                    tx.transactional {
                        testEntityRepository.deleteById(99999L)
                    }
                }
            }

            context("delete") {
                it("deletes an entity") {
                    val saved = tx.transactional {
                        testEntityRepository.save(TestEntity(name = "delete", value = 1))
                    }

                    tx.transactional {
                        testEntityRepository.delete(saved)
                    }

                    val found = tx.readOnly {
                        testEntityRepository.findById(saved.id!!)
                    }
                    found.shouldBeNull()
                }
            }
        }
    }
}
