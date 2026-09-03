package io.clroot.hibernate.reactive.repository.auditing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class AuditingEntityLifecycleTest : DescribeSpec({
    describe("annotation-based auditing lifecycle") {
        it("sets both auditor fields for a new entity") {
            val entity = AuditedEntity()
            val lifecycle = AuditingEntityLifecycle(ReactiveAuditorAware { "creator" })

            lifecycle.beforeSave(entity, isNew = true)

            entity.createdBy shouldBe "creator"
            entity.updatedBy shouldBe "creator"
        }

        it("updates only the modified auditor for an existing entity") {
            val entity = AuditedEntity(createdBy = "creator", updatedBy = "creator")
            val lifecycle = AuditingEntityLifecycle(ReactiveAuditorAware { "modifier" })

            lifecycle.beforeSave(entity, isNew = false)

            entity.createdBy shouldBe "creator"
            entity.updatedBy shouldBe "modifier"
        }

        it("does not overwrite a preassigned creator") {
            val entity = AuditedEntity(createdBy = "importer")
            val lifecycle = AuditingEntityLifecycle(ReactiveAuditorAware { "system" })

            lifecycle.beforeSave(entity, isNew = true)

            entity.createdBy shouldBe "importer"
            entity.updatedBy shouldBe "system"
        }

        it("leaves fields unchanged when no auditor is available") {
            val entity = AuditedEntity()
            val lifecycle = AuditingEntityLifecycle(ReactiveAuditorAware<String> { null })

            lifecycle.beforeSave(entity, isNew = true)

            entity.createdBy shouldBe null
            entity.updatedBy shouldBe null
        }

        it("rejects an auditor whose type does not match the annotated field") {
            val lifecycle = AuditingEntityLifecycle(ReactiveAuditorAware { 42L })

            shouldThrow<IllegalArgumentException> {
                lifecycle.beforeSave(AuditedEntity(), isNew = true)
            }
        }
    }
})

private class AuditedEntity(
    @CreatedBy
    var createdBy: String? = null,
    @LastModifiedBy
    var updatedBy: String? = null,
)
