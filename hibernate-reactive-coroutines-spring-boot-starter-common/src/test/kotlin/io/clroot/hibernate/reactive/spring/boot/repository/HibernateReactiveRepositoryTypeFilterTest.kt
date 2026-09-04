package io.clroot.hibernate.reactive.spring.boot.repository

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.type.classreading.CachingMetadataReaderFactory
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

class HibernateReactiveRepositoryTypeFilterTest : DescribeSpec({

    val resourceLoader = DefaultResourceLoader()
    val metadataReaderFactory = CachingMetadataReaderFactory(resourceLoader)
    val filter = HibernateReactiveRepositoryTypeFilter()

    fun createMetadataReader(clazz: Class<*>) =
        metadataReaderFactory.getMetadataReader(clazz.name)

    describe("HibernateReactiveRepositoryTypeFilter") {

        context("match") {

            it("matches an interface that directly extends CoroutineCrudRepository") {
                val reader = createMetadataReader(DirectRepository::class.java)
                filter.match(reader, metadataReaderFactory) shouldBe true
            }

            it("matches an interface that indirectly extends CoroutineCrudRepository") {
                val reader = createMetadataReader(IndirectRepository::class.java)
                filter.match(reader, metadataReaderFactory) shouldBe true
            }

            it("does not match CoroutineCrudRepository itself") {
                val reader = createMetadataReader(CoroutineCrudRepository::class.java)
                filter.match(reader, metadataReaderFactory) shouldBe false
            }

            it("does not match an unrelated interface") {
                val reader = createMetadataReader(UnrelatedInterface::class.java)
                filter.match(reader, metadataReaderFactory) shouldBe false
            }

            it("does not match a class") {
                val reader = createMetadataReader(SomeClass::class.java)
                filter.match(reader, metadataReaderFactory) shouldBe false
            }
        }
    }
}) {
    companion object {
        interface DirectRepository : CoroutineCrudRepository<TestEntity, Long>
        interface IndirectRepository : DirectRepository
        interface UnrelatedInterface
        class SomeClass
        class TestEntity
    }
}
