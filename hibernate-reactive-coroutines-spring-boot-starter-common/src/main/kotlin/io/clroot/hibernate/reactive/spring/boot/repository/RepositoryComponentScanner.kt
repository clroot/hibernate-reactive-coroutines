package io.clroot.hibernate.reactive.spring.boot.repository

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider

/**
 * Scans repository interfaces as component candidates.
 *
 * Spring's default [ClassPathScanningCandidateComponentProvider] excludes interfaces, but
 * repository implementations are created from their interface definitions.
 */
internal class RepositoryComponentScanner : ClassPathScanningCandidateComponentProvider(false) {

    override fun isCandidateComponent(beanDefinition: AnnotatedBeanDefinition): Boolean {
        return beanDefinition.metadata.isInterface
    }
}
