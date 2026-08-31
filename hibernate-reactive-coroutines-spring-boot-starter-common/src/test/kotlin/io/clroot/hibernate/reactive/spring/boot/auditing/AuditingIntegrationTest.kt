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
            context("엔티티 생성 시") {
                it("@CreatedDate 필드에 현재 시간이 설정된다") {
                    // given
                    val entity = AuditableEntity(name = "test")

                    // when
                    val saved = repository.save(entity)

                    // then
                    saved.createdAt.shouldNotBeNull()
                }

                it("@LastModifiedDate 필드에 현재 시간이 설정된다") {
                    // given
                    val entity = AuditableEntity(name = "test")

                    // when
                    val saved = repository.save(entity)

                    // then
                    saved.updatedAt.shouldNotBeNull()
                }

                it("AuditorAware가 설정되면 @CreatedBy 필드에 감사자가 설정된다") {
                    // given
                    TestAuditorAware.setCurrentAuditor("testUser")
                    val entity = AuditableEntity(name = "test")

                    // when
                    val saved = repository.save(entity)

                    // then
                    saved.createdBy shouldBe "testUser"
                }

                it("AuditorAware가 설정되면 @LastModifiedBy 필드에 감사자가 설정된다") {
                    // given
                    TestAuditorAware.setCurrentAuditor("testUser")
                    val entity = AuditableEntity(name = "test")

                    // when
                    val saved = repository.save(entity)

                    // then
                    saved.updatedBy shouldBe "testUser"
                }

                it("AuditorAware가 null을 반환하면 @CreatedBy는 null이다") {
                    // given
                    TestAuditorAware.setCurrentAuditor(null)
                    val entity = AuditableEntity(name = "test")

                    // when
                    val saved = repository.save(entity)

                    // then
                    saved.createdBy.shouldBeNull()
                }
            }

            context("엔티티 수정 시") {
                it("@LastModifiedDate 필드가 업데이트된다") {
                    // given
                    val entity = AuditableEntity(name = "test")
                    val saved = repository.save(entity)
                    val originalUpdatedAt = saved.updatedAt

                    // 시간 차이를 위해 잠시 대기
                    delay(10)

                    // when
                    saved.name = "updated"
                    val updated = repository.save(saved)

                    // then
                    updated.updatedAt shouldNotBe originalUpdatedAt
                }

                it("@CreatedDate 필드는 변경되지 않는다") {
                    // given
                    val entity = AuditableEntity(name = "test")
                    val saved = repository.save(entity)
                    val originalCreatedAt = saved.createdAt

                    // when
                    saved.name = "updated"
                    val updated = repository.save(saved)

                    // then
                    updated.createdAt shouldBe originalCreatedAt
                }

                it("@LastModifiedBy 필드가 업데이트된다") {
                    // given
                    TestAuditorAware.setCurrentAuditor("creator")
                    val entity = AuditableEntity(name = "test")
                    val saved = repository.save(entity)

                    // when
                    TestAuditorAware.setCurrentAuditor("modifier")
                    saved.name = "updated"
                    val updated = repository.save(saved)

                    // then
                    updated.createdBy shouldBe "creator"
                    updated.updatedBy shouldBe "modifier"
                }

                it("@CreatedBy 필드는 변경되지 않는다") {
                    // given
                    TestAuditorAware.setCurrentAuditor("creator")
                    val entity = AuditableEntity(name = "test")
                    val saved = repository.save(entity)

                    // when
                    TestAuditorAware.setCurrentAuditor("modifier")
                    saved.name = "updated"
                    val updated = repository.save(saved)

                    // then
                    updated.createdBy shouldBe "creator"
                }
            }
        }
    }
}
