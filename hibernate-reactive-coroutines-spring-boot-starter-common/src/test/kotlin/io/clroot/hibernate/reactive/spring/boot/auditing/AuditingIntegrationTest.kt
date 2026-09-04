package io.clroot.hibernate.reactive.spring.boot.auditing

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.test.IntegrationTestBase
import io.clroot.hibernate.reactive.test.TestApplication
import io.clroot.hibernate.reactive.test.auditing.TestAuditorAware
import io.clroot.hibernate.reactive.test.entity.AuditableEntity
import io.clroot.hibernate.reactive.test.repository.AuditableEntityRepository
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.delay
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(classes = [TestApplication::class])
class AuditingIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var repository: AuditableEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        beforeEach {
            tx.transactional {
                repository.deleteAll()
            }
        }

        afterEach {
            TestAuditorAware.clear()
        }

        describe("Auditing") {
            context("when creating an entity") {
                it("sets the current time on the @CreatedDate field") {
                    val entity = AuditableEntity(name = "test")

                    val saved = repository.save(entity)

                    saved.createdAt.shouldNotBeNull()
                }

                it("sets the current time on the @LastModifiedDate field") {
                    val entity = AuditableEntity(name = "test")

                    val saved = repository.save(entity)

                    saved.updatedAt.shouldNotBeNull()
                }

                it("sets the auditor on the @CreatedBy field when AuditorAware is configured") {
                    TestAuditorAware.setCurrentAuditor("testUser")
                    val entity = AuditableEntity(name = "test")

                    val saved = repository.save(entity)

                    saved.createdBy shouldBe "testUser"
                }

                it("sets the auditor on the @LastModifiedBy field when AuditorAware is configured") {
                    TestAuditorAware.setCurrentAuditor("testUser")
                    val entity = AuditableEntity(name = "test")

                    val saved = repository.save(entity)

                    saved.updatedBy shouldBe "testUser"
                }

                it("leaves @CreatedBy null when AuditorAware returns null") {
                    TestAuditorAware.setCurrentAuditor(null)
                    val entity = AuditableEntity(name = "test")

                    val saved = repository.save(entity)

                    saved.createdBy.shouldBeNull()
                }
            }

            context("when updating an entity") {
                it("updates the @LastModifiedDate field") {
                    val entity = AuditableEntity(name = "test")
                    val saved = repository.save(entity)
                    val originalUpdatedAt = saved.updatedAt

                    // Ensure the generated timestamp differs from the original.
                    delay(10)

                    saved.name = "updated"
                    val updated = repository.save(saved)

                    updated.updatedAt shouldNotBe originalUpdatedAt
                }

                it("does not change the @CreatedDate field") {
                    val entity = AuditableEntity(name = "test")
                    val saved = repository.save(entity)
                    val originalCreatedAt = saved.createdAt

                    saved.name = "updated"
                    val updated = repository.save(saved)

                    updated.createdAt shouldBe originalCreatedAt
                }

                it("updates the @LastModifiedBy field") {
                    TestAuditorAware.setCurrentAuditor("creator")
                    val entity = AuditableEntity(name = "test")
                    val saved = repository.save(entity)

                    TestAuditorAware.setCurrentAuditor("modifier")
                    saved.name = "updated"
                    val updated = repository.save(saved)

                    updated.createdBy shouldBe "creator"
                    updated.updatedBy shouldBe "modifier"
                }

                it("does not change the @CreatedBy field") {
                    TestAuditorAware.setCurrentAuditor("creator")
                    val entity = AuditableEntity(name = "test")
                    val saved = repository.save(entity)

                    TestAuditorAware.setCurrentAuditor("modifier")
                    saved.name = "updated"
                    val updated = repository.save(saved)

                    updated.createdBy shouldBe "creator"
                }
            }
        }
    }
}
