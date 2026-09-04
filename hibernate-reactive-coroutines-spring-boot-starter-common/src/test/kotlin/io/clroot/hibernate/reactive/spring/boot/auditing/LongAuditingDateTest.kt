package io.clroot.hibernate.reactive.spring.boot.auditing

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate

class LongAuditingDateTest : DescribeSpec({
    val listener = AuditingEntityListener()

    describe("Long auditing dates") {
        it("sets nullable Long date fields to epoch milliseconds") {
            val entity = NullableLongDates()

            listener.onPrePersist(entity)

            entity.createdAt.shouldNotBeNull() shouldBeGreaterThan 0L
            entity.updatedAt.shouldNotBeNull() shouldBeGreaterThan 0L
        }

        it("replaces zero-valued non-null Long fields with epoch milliseconds") {
            val entity = NonNullLongDates()

            listener.onPrePersist(entity)

            entity.createdAt shouldBeGreaterThan 0L
            entity.updatedAt shouldBeGreaterThan 0L
        }

        it("preserves an explicitly set nullable Long creation timestamp") {
            val entity = NullableLongDates(createdAt = 123L)

            listener.onPrePersist(entity)

            entity.createdAt shouldBe 123L
        }

        it("preserves an explicitly set non-null Long creation timestamp") {
            val entity = NonNullLongDates(createdAt = 123L)

            listener.onPrePersist(entity)

            entity.createdAt shouldBe 123L
        }

        it("updates nullable Long modification timestamps") {
            val entity = NullableLongDates(createdAt = 123L, updatedAt = 123L)

            listener.onPreUpdate(entity)

            entity.createdAt shouldBe 123L
            entity.updatedAt.shouldNotBeNull() shouldBeGreaterThan 123L
        }

        it("updates non-null Long modification timestamps while preserving creation timestamps") {
            val entity = NonNullLongDates(createdAt = 123L, updatedAt = 123L)

            listener.onPreUpdate(entity)

            entity.createdAt shouldBe 123L
            entity.updatedAt shouldBeGreaterThan 123L
        }
    }
}) {
    private class NullableLongDates(
        @CreatedDate
        var createdAt: Long? = null,
        @LastModifiedDate
        var updatedAt: Long? = null,
    )

    private class NonNullLongDates(
        @CreatedDate
        var createdAt: Long = 0,
        @LastModifiedDate
        var updatedAt: Long = 0,
    )
}
