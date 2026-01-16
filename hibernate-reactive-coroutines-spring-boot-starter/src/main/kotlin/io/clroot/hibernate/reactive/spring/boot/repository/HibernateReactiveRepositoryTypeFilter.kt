package io.clroot.hibernate.reactive.spring.boot.repository

import org.springframework.core.type.ClassMetadata
import org.springframework.core.type.classreading.MetadataReader
import org.springframework.core.type.classreading.MetadataReaderFactory
import org.springframework.core.type.filter.TypeFilter
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.io.IOException

/**
 * CoroutineCrudRepository를 상속하는 인터페이스를 찾는 TypeFilter.
 *
 * ClassPath 스캔 시 [CoroutineCrudRepository]를 상속하는 인터페이스만 필터링합니다.
 * 클래스 로딩 없이 메타데이터만 사용하여 효율적으로 필터링합니다.
 */
internal class HibernateReactiveRepositoryTypeFilter : TypeFilter {

    private val coroutineCrudRepositoryName = CoroutineCrudRepository::class.java.name

    override fun match(metadataReader: MetadataReader, metadataReaderFactory: MetadataReaderFactory): Boolean {
        val classMetadata = metadataReader.classMetadata

        // 인터페이스가 아니면 제외
        if (!classMetadata.isInterface) return false

        // CoroutineCrudRepository 자체는 제외
        if (classMetadata.className == coroutineCrudRepositoryName) return false

        // CoroutineCrudRepository를 상속하는지 확인 (메타데이터 기반 재귀 탐색)
        return isAssignableToCoroutineCrudRepository(classMetadata, metadataReaderFactory)
    }

    /**
     * 메타데이터를 사용하여 CoroutineCrudRepository를 상속하는지 확인합니다.
     * 클래스 로딩 없이 재귀적으로 인터페이스 계층을 탐색합니다.
     */
    private fun isAssignableToCoroutineCrudRepository(
        metadata: ClassMetadata,
        factory: MetadataReaderFactory,
    ): Boolean {
        // 직접 CoroutineCrudRepository를 상속하는지 확인
        if (metadata.interfaceNames.contains(coroutineCrudRepositoryName)) {
            return true
        }

        // 상위 인터페이스를 재귀적으로 탐색
        for (interfaceName in metadata.interfaceNames) {
            try {
                val reader = factory.getMetadataReader(interfaceName)
                if (isAssignableToCoroutineCrudRepository(reader.classMetadata, factory)) {
                    return true
                }
            } catch (_: IOException) {
                // 메타데이터를 읽을 수 없는 경우 무시하고 계속
            }
        }

        return false
    }
}
