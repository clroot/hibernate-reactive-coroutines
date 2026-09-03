package io.clroot.hibernate.reactive.repository.runtime

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Id
import jakarta.persistence.Version

class EntityStateDetectorTest : DescribeSpec({
    describe("repository entity state") {
        it("treats a primitive zero identifier as new") {
            EntityStateDetector.isNew(PrimitiveIdEntity()).shouldBeTrue()
            EntityStateDetector.isNew(PrimitiveIdEntity(1L)).shouldBeFalse()
        }

        it("treats only a null nullable version as new when a version is available") {
            EntityStateDetector.isNew(VersionedEntity(id = 1L, version = null)).shouldBeTrue()
            EntityStateDetector.isNew(VersionedEntity(id = 1L, version = 0L)).shouldBeFalse()
        }

        it("reads identifiers from property-access getters") {
            EntityStateDetector.isNew(PropertyAccessEntity(null)).shouldBeTrue()
            EntityStateDetector.isNew(PropertyAccessEntity(1L)).shouldBeFalse()
        }

        it("reads embedded identifiers") {
            EntityStateDetector.isNew(EmbeddedIdEntity(null)).shouldBeTrue()
            EntityStateDetector.isNew(EmbeddedIdEntity(ExampleId(1L))).shouldBeFalse()
        }

        it("reads inherited identifiers") {
            EntityStateDetector.isNew(InheritedIdEntity(null)).shouldBeTrue()
            EntityStateDetector.isNew(InheritedIdEntity(1L)).shouldBeFalse()
        }
    }
})

private class PrimitiveIdEntity(
    @field:Id
    var id: Long = 0,
)

private class VersionedEntity(
    @field:Id
    var id: Long?,
    @field:Version
    var version: Long?,
)

private class PropertyAccessEntity(
    private val identifier: Long?,
) {
    @get:Id
    val id: Long?
        get() = identifier
}

@Embeddable
private data class ExampleId(
    val value: Long,
)

private class EmbeddedIdEntity(
    @field:EmbeddedId
    var id: ExampleId?,
)

private open class IdBase(
    @field:Id
    var id: Long?,
)

private class InheritedIdEntity(
    id: Long?,
) : IdBase(id)
