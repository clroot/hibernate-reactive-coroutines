package io.clroot.hibernate.reactive.spring.boot.repository

import org.springframework.core.type.ClassMetadata
import org.springframework.core.type.classreading.MetadataReader
import org.springframework.core.type.classreading.MetadataReaderFactory
import org.springframework.core.type.filter.TypeFilter
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.io.IOException

/**
 * Matches interfaces that extend [CoroutineCrudRepository].
 *
 * Traverses interface metadata without loading candidate classes, preventing class-loading side effects during
 * component scanning.
 */
internal class HibernateReactiveRepositoryTypeFilter : TypeFilter {

    private val coroutineCrudRepositoryName = CoroutineCrudRepository::class.java.name

    override fun match(metadataReader: MetadataReader, metadataReaderFactory: MetadataReaderFactory): Boolean {
        val classMetadata = metadataReader.classMetadata

        if (!classMetadata.isInterface) return false

        if (classMetadata.className == coroutineCrudRepositoryName) return false

        return isAssignableToCoroutineCrudRepository(classMetadata, metadataReaderFactory)
    }

    /**
     * Traverses the interface hierarchy through metadata without loading classes.
     */
    private fun isAssignableToCoroutineCrudRepository(
        metadata: ClassMetadata,
        factory: MetadataReaderFactory,
    ): Boolean {
        if (metadata.interfaceNames.contains(coroutineCrudRepositoryName)) {
            return true
        }

        for (interfaceName in metadata.interfaceNames) {
            try {
                val reader = factory.getMetadataReader(interfaceName)
                if (isAssignableToCoroutineCrudRepository(reader.classMetadata, factory)) {
                    return true
                }
            } catch (_: IOException) {
            }
        }

        return false
    }
}
