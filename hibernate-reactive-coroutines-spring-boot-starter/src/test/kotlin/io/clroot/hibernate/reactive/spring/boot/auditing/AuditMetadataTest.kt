package io.clroot.hibernate.reactive.spring.boot.auditing

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/**
 * auditing 타임스탬프 처리를 검증합니다.
 */
class AuditMetadataTest : DescribeSpec({

    describe("지원 시간 타입") {

        it("OffsetDateTime 필드를 채운다") {
            val entity = OffsetDateTimeEntity()

            AuditMetadata.setCreatedDate(entity)
            AuditMetadata.setLastModifiedDate(entity)

            entity.createdAt.shouldNotBeNull()
            entity.updatedAt.shouldNotBeNull()
        }

        it("ZonedDateTime 필드를 채운다") {
            val entity = ZonedDateTimeEntity()

            AuditMetadata.setCreatedDate(entity)

            entity.createdAt.shouldNotBeNull()
        }
    }

    describe("생성 시각") {

        it("같은 기준 시각을 넘기면 생성/수정 시각이 동일하다") {
            val entity = OffsetDateTimeEntity()
            val now = Instant.now()

            AuditMetadata.setCreatedDate(entity, now)
            AuditMetadata.setLastModifiedDate(entity, now)

            entity.createdAt!!.toInstant() shouldBe entity.updatedAt!!.toInstant()
        }
    }

    describe("지원하지 않는 타입") {

        it("필드를 건드리지 않는다") {
            val entity = UnsupportedTypeEntity()

            AuditMetadata.setCreatedDate(entity)

            entity.createdAt shouldBe null
        }
    }
})

private class OffsetDateTimeEntity {
    @CreatedDate
    var createdAt: OffsetDateTime? = null

    @LastModifiedDate
    var updatedAt: OffsetDateTime? = null
}

private class ZonedDateTimeEntity {
    @CreatedDate
    var createdAt: ZonedDateTime? = null
}

private class UnsupportedTypeEntity {
    @CreatedDate
    var createdAt: StringBuilder? = null
}
