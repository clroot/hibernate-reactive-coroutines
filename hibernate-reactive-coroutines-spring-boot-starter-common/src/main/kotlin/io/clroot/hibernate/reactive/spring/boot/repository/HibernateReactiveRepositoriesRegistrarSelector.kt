package io.clroot.hibernate.reactive.spring.boot.repository

import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.type.AnnotationMetadata

/**
 * @EnableHibernateReactiveRepositories 어노테이션을 처리하여 적절한 Registrar를 선택합니다.
 */
public class HibernateReactiveRepositoriesRegistrarSelector : ImportBeanDefinitionRegistrar {

    override fun registerBeanDefinitions(
        importingClassMetadata: AnnotationMetadata,
        registry: BeanDefinitionRegistry,
    ) {
        val attributes = importingClassMetadata.getAnnotationAttributes(
            EnableHibernateReactiveRepositories::class.java.name,
        ) ?: return

        val declaredPackages = resolveBasePackages(attributes, importingClassMetadata)

        // 여러 설정 클래스가 각자 @EnableHibernateReactiveRepositories를 선언할 수 있으므로,
        // 앞서 처리된 선언의 패키지와 합친다. 덮어쓰면 먼저 처리된 모듈의 Repository가 등록되지 않는다.
        val basePackages = (previouslyDeclaredPackages(registry) + declaredPackages).distinct()

        // auto-config가 등록한 기본 registrar 또는 이전 선언의 registrar를 대체한다
        if (registry.containsBeanDefinition(REGISTRAR_BEAN_NAME)) {
            registry.removeBeanDefinition(REGISTRAR_BEAN_NAME)
        }

        val beanDefinition = BeanDefinitionBuilder
            .genericBeanDefinition(HibernateReactiveRepositoryRegistrar::class.java)
            .addConstructorArgValue(basePackages)
            .beanDefinition
            .apply { setAttribute(DECLARED_PACKAGES_ATTRIBUTE, basePackages) }

        registry.registerBeanDefinition(REGISTRAR_BEAN_NAME, beanDefinition)
    }

    /**
     * 이전 `@EnableHibernateReactiveRepositories` 선언이 등록한 패키지를 반환합니다.
     *
     * auto-config가 등록한 기본 registrar에는 이 속성이 없으므로 빈 목록이 반환되고,
     * 결과적으로 명시적 선언이 기본 스캔을 대체합니다.
     */
    @Suppress("UNCHECKED_CAST")
    private fun previouslyDeclaredPackages(registry: BeanDefinitionRegistry): List<String> {
        if (!registry.containsBeanDefinition(REGISTRAR_BEAN_NAME)) return emptyList()

        return registry.getBeanDefinition(REGISTRAR_BEAN_NAME)
            .getAttribute(DECLARED_PACKAGES_ATTRIBUTE) as? List<String>
            ?: emptyList()
    }

    private fun resolveBasePackages(
        attributes: Map<String, Any?>,
        importingClassMetadata: AnnotationMetadata,
    ): List<String> {
        val packages = mutableSetOf<String>()

        // value 또는 basePackages 속성
        @Suppress("UNCHECKED_CAST")
        val basePackagesAttr = attributes["basePackages"] as? Array<String> ?: emptyArray()
        packages.addAll(basePackagesAttr)

        // basePackageClasses 속성
        @Suppress("UNCHECKED_CAST")
        val basePackageClasses = attributes["basePackageClasses"] as? Array<Class<*>> ?: emptyArray()
        basePackageClasses.forEach { clazz ->
            packages.add(clazz.packageName)
        }

        // 아무것도 지정되지 않으면 어노테이션이 붙은 클래스의 패키지 사용
        // (클래스를 로딩하면 앱 클래스로더와 어긋날 수 있으므로 이름에서 패키지를 얻는다)
        if (packages.isEmpty()) {
            packages.add(importingClassMetadata.className.substringBeforeLast('.', ""))
        }

        return packages.toList()
    }

    private companion object {
        private const val REGISTRAR_BEAN_NAME = "hibernateReactiveRepositoryRegistrar"

        /** 이 selector가 등록한 정의임을 표시하고, 누적된 패키지를 담는다. */
        private const val DECLARED_PACKAGES_ATTRIBUTE =
            "io.clroot.hibernate.reactive.declaredBasePackages"
    }
}
