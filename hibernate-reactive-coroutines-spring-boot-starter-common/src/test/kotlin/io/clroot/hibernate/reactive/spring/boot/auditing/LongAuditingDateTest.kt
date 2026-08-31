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
        it("nullable Long 날짜 필드를 epoch milliseconds로 설정한다") {
            val entity = NullableLongDates()

            listener.onPrePersist(entity)

            entity.createdAt.shouldNotBeNull() shouldBeGreaterThan 0L
            entity.updatedAt.shouldNotBeNull() shouldBeGreaterThan 0L
        }

        it("non-null Long의 초기값 0을 epoch milliseconds로 교체한다") {
            val entity = NonNullLongDates()

            listener.onPrePersist(entity)

            entity.createdAt shouldBeGreaterThan 0L
            entity.updatedAt shouldBeGreaterThan 0L
        }

        it("명시적으로 설정된 nullable Long 생성 시각은 보존한다") {
            val entity = NullableLongDates(createdAt = 123L)

            listener.onPrePersist(entity)

            entity.createdAt shouldBe 123L
        }

        it("명시적으로 설정된 non-null Long 생성 시각은 보존한다") {
            val entity = NonNullLongDates(createdAt = 123L)

            listener.onPrePersist(entity)

            entity.createdAt shouldBe 123L
        }

        it("nullable Long 수정 시각을 갱신한다") {
            val entity = NullableLongDates(createdAt = 123L, updatedAt = 123L)

            listener.onPreUpdate(entity)

            entity.createdAt shouldBe 123L
            entity.updatedAt.shouldNotBeNull() shouldBeGreaterThan 123L
        }

        it("non-null Long 수정 시각을 갱신하고 생성 시각은 보존한다") {
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
