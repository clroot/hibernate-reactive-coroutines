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

            it("CoroutineCrudRepository를 직접 상속하는 인터페이스는 true") {
                val reader = createMetadataReader(DirectRepository::class.java)
                filter.match(reader, metadataReaderFactory) shouldBe true
            }

            it("CoroutineCrudRepository를 간접 상속하는 인터페이스는 true") {
                val reader = createMetadataReader(IndirectRepository::class.java)
                filter.match(reader, metadataReaderFactory) shouldBe true
            }

            it("CoroutineCrudRepository 자체는 false") {
                val reader = createMetadataReader(CoroutineCrudRepository::class.java)
                filter.match(reader, metadataReaderFactory) shouldBe false
            }

            it("CoroutineCrudRepository를 상속하지 않는 인터페이스는 false") {
                val reader = createMetadataReader(UnrelatedInterface::class.java)
                filter.match(reader, metadataReaderFactory) shouldBe false
            }

            it("클래스(인터페이스가 아닌)는 false") {
                val reader = createMetadataReader(SomeClass::class.java)
                filter.match(reader, metadataReaderFactory) shouldBe false
            }
        }
    }
}) {
    companion object {
        // 테스트용 타입들
        interface DirectRepository : CoroutineCrudRepository<TestEntity, Long>
        interface IndirectRepository : DirectRepository
        interface UnrelatedInterface
        class SomeClass
        class TestEntity
    }
}
