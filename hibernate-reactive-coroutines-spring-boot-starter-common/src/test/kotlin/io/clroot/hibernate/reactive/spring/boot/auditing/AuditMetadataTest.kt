package io.clroot.hibernate.reactive.spring.boot.auditing

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/** Verifies auditing timestamp handling. */
class AuditMetadataTest : DescribeSpec({

    describe("supported time types") {

        it("populates OffsetDateTime fields") {
            val entity = OffsetDateTimeEntity()

            AuditMetadata.setCreatedDate(entity)
            AuditMetadata.setLastModifiedDate(entity)

            entity.createdAt.shouldNotBeNull()
            entity.updatedAt.shouldNotBeNull()
        }

        it("populates ZonedDateTime fields") {
            val entity = ZonedDateTimeEntity()

            AuditMetadata.setCreatedDate(entity)

            entity.createdAt.shouldNotBeNull()
        }
    }

    describe("creation timestamps") {

        it("uses the same timestamp for creation and modification when given the same instant") {
            val entity = OffsetDateTimeEntity()
            val now = Instant.now()

            AuditMetadata.setCreatedDate(entity, now)
            AuditMetadata.setLastModifiedDate(entity, now)

            entity.createdAt!!.toInstant() shouldBe entity.updatedAt!!.toInstant()
        }
    }

    describe("unsupported types") {

        it("leaves the field unchanged") {
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
