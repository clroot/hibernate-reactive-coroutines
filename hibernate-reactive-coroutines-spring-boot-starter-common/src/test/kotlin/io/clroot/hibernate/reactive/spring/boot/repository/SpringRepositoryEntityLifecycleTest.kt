package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.spring.boot.auditing.ReactiveAuditingHandler
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.mockk
import org.springframework.data.domain.Persistable

class SpringRepositoryEntityLifecycleTest : DescribeSpec({
    describe("Spring repository entity lifecycle adapter") {
        it("preserves Persistable new-state precedence") {
            val lifecycle = SpringRepositoryEntityLifecycle(null)

            lifecycle.isNew(PersistableEntity(true)) shouldBe true
            lifecycle.isNew(PersistableEntity(false)) shouldBe false
            lifecycle.isNew(Any()) shouldBe null
        }

        it("delegates create and update callbacks to reactive auditing") {
            val auditing = mockk<ReactiveAuditingHandler<Any>>(relaxed = true)
            val lifecycle = SpringRepositoryEntityLifecycle(auditing)
            val created = Any()
            val modified = Any()

            lifecycle.beforeSave(created, true)
            lifecycle.beforeSave(modified, false)

            coVerify(exactly = 1) { auditing.markCreated(created) }
            coVerify(exactly = 1) { auditing.markModified(modified) }
        }
    }
}) {
    private class PersistableEntity(private val newState: Boolean) : Persistable<Long> {
        override fun getId(): Long = 1L
        override fun isNew(): Boolean = newState
    }
}
