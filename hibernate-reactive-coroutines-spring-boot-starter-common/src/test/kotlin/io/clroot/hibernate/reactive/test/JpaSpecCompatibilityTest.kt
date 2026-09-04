package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.spring.boot.repository.EnableHibernateReactiveRepositories
import io.clroot.hibernate.reactive.test.service.JpaSpecTestService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.ComponentScan

/**
 * Verifies JPA specification compatibility.
 *
 * Confirms that Hibernate Reactive Coroutines provides behavior Spring Data JPA users expect.
 */
@SpringBootTest
@EnableHibernateReactiveRepositories(basePackages = ["io.clroot.hibernate.reactive.test.repository"])
@ComponentScan(basePackages = ["io.clroot.hibernate.reactive.test.service"])
class JpaSpecCompatibilityTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jpaSpecTestService: JpaSpecTestService

    init {
        describe("Dirty Checking") {
            context("when an entity is modified within a transaction") {
                it("persists the change at commit without an explicit save") {
                    val saved = jpaSpecTestService.saveEntity("original", 100)
                    val id = saved.id!!

                    jpaSpecTestService.modifyEntityWithoutSave(id, "modified", 200)

                    val reloaded = jpaSpecTestService.findById(id)
                    reloaded shouldNotBe null
                    reloaded!!.name shouldBe "modified"
                    reloaded.value shouldBe 200
                }

                it("persists changes to multiple fields") {
                    val saved = jpaSpecTestService.saveEntity("test", 50)
                    val id = saved.id!!

                    jpaSpecTestService.modifyMultipleFields(id, "newName", 999)

                    val reloaded = jpaSpecTestService.findById(id)
                    reloaded!!.name shouldBe "newName"
                    reloaded.value shouldBe 999
                }
            }

            context("when an entity is modified in a read-only transaction") {
                it("does not persist the change") {
                    val saved = jpaSpecTestService.saveEntity("readonly-test", 100)
                    val id = saved.id!!

                    jpaSpecTestService.modifyInReadOnlyTransaction(id, "should-not-persist", 999)

                    val reloaded = jpaSpecTestService.findById(id)
                    reloaded!!.name shouldBe "readonly-test"
                    reloaded.value shouldBe 100
                }
            }

            context("when a transaction rolls back") {
                it("rolls back dirty-checked changes") {
                    val saved = jpaSpecTestService.saveEntity("rollback-test", 100)
                    val id = saved.id!!

                    runCatching {
                        jpaSpecTestService.modifyAndThrowException(id, "should-rollback", 999)
                    }

                    val reloaded = jpaSpecTestService.findById(id)
                    reloaded!!.name shouldBe "rollback-test"
                    reloaded.value shouldBe 100
                }
            }
        }

        describe("First-level Cache (persistence context)") {
            context("when the same ID is fetched in one transaction") {
                it("returns the same object instance") {
                    val saved = jpaSpecTestService.saveEntity("cache-test", 100)
                    val id = saved.id!!

                    jpaSpecTestService.verifyFirstLevelCache(id)
                }
            }

            context("when the entity is fetched in separate transactions") {
                it("returns different object instances") {
                    val saved = jpaSpecTestService.saveEntity("cache-test-2", 100)
                    val id = saved.id!!

                    val entity1 = jpaSpecTestService.findById(id)
                    val entity2 = jpaSpecTestService.findById(id)

                    (entity1 !== entity2) shouldBe true
                    entity1!!.name shouldBe entity2!!.name
                }
            }
        }

        describe("Optimistic Locking (@Version)") {
            context("when an entity has an @Version field") {
                it("increments the version automatically") {
                    val saved = jpaSpecTestService.saveVersionedEntity("version-test", 100)

                    saved.version shouldBe 0L

                    val updated = jpaSpecTestService.updateVersionedEntity(saved.id!!, "updated", 200)

                    updated.version shouldBe 1L
                }

                it("merges and updates a detached entity with initial version zero") {
                    val detached = jpaSpecTestService.saveVersionedEntity("detached-version-zero", 100)
                    detached.version shouldBe 0L
                    detached.name = "detached-updated"

                    val updated = jpaSpecTestService.saveVersionedEntity(detached)

                    updated.id shouldBe detached.id
                    updated.name shouldBe "detached-updated"
                    updated.version shouldBe 1L
                }
            }

            context("when the same entity is modified concurrently") {
                it("throws an OptimisticLockException") {
                    val saved = jpaSpecTestService.saveVersionedEntity("concurrent-test", 100)
                    val id = saved.id!!

                    shouldThrow<Exception> {
                        jpaSpecTestService.concurrentUpdate(id)
                    }
                }
            }
        }

        describe("Lazy Loading") {
            context("in a Hibernate Reactive environment") {
                it("accesses lazy collections within the same transaction") {
                    val parentId = jpaSpecTestService.saveParentWithChildren("parent", listOf("child1", "child2"))

                    val childCount = jpaSpecTestService.getChildCountInTransaction(parentId)
                    childCount shouldBe 2
                }

                it("accesses JOIN FETCH-loaded collections outside the transaction") {
                    val parentId =
                        jpaSpecTestService.saveParentWithChildren("parent", listOf("child1", "child2", "child3"))

                    val parent = jpaSpecTestService.findParentWithChildrenEager(parentId)

                    parent shouldNotBe null
                    parent!!.children.size shouldBe 3
                }
            }

            context("when using the sessionProvider.fetch() convenience method") {
                it("loads lazy associations") {
                    val parentId = jpaSpecTestService.saveParentWithChildren("fetch-test", listOf("a", "b", "c"))

                    val children = jpaSpecTestService.getChildrenUsingFetch(parentId)

                    children.size shouldBe 3
                    children.map { it.name } shouldBe listOf("a", "b", "c")
                }
            }

            context("when using the sessionProvider.fetchAll() convenience method") {
                it("loads multiple lazy associations at once") {
                    val parentId = jpaSpecTestService.saveParentWithChildren("fetchAll-test", listOf("x", "y"))

                    val parent = jpaSpecTestService.getParentWithAllAssociations(parentId)

                    parent.children.size shouldBe 2
                }
            }

            context("when using the sessionProvider.fetchFromDetached() convenience method") {
                it("loads lazy associations from a detached entity") {
                    val parentId = jpaSpecTestService.saveParentWithChildren("detached-test", listOf("d1", "d2"))
                    val detachedParent = jpaSpecTestService.findParentWithChildrenEager(parentId)!!

                    val children = jpaSpecTestService.getChildrenFromDetachedParent(detachedParent)

                    children.size shouldBe 2
                }
            }
        }

        describe("Flush behavior") {
            context("before transaction commit") {
                it("flushes changes so queries observe them") {
                    jpaSpecTestService.verifyFlushBeforeQuery()
                }
            }
        }
    }
}
