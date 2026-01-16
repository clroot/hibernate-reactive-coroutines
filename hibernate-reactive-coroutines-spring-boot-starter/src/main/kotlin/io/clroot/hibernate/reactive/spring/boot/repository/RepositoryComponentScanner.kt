package io.clroot.hibernate.reactive.spring.boot.repository

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider

/**
 * 인터페이스도 스캔하는 커스텀 ClassPath 스캐너.
 *
 * 기본 [ClassPathScanningCandidateComponentProvider]는 인터페이스를 후보에서 제외하지만,
 * Repository 인터페이스를 스캔해야 하므로 이를 오버라이드합니다.
 */
internal class RepositoryComponentScanner : ClassPathScanningCandidateComponentProvider(false) {

    override fun isCandidateComponent(beanDefinition: AnnotatedBeanDefinition): Boolean {
        // 인터페이스도 후보로 포함
        return beanDefinition.metadata.isInterface
    }
}
