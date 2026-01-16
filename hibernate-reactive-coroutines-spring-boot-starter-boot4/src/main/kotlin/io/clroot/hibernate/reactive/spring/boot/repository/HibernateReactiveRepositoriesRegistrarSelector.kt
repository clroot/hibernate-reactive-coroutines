package io.clroot.hibernate.reactive.spring.boot.repository

import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.type.AnnotationMetadata

/**
 * @EnableHibernateReactiveRepositories 어노테이션을 처리하여 적절한 Registrar를 선택합니다.
 */
class HibernateReactiveRepositoriesRegistrarSelector : ImportBeanDefinitionRegistrar {

    override fun registerBeanDefinitions(
        importingClassMetadata: AnnotationMetadata,
        registry: BeanDefinitionRegistry,
    ) {
        val attributes = importingClassMetadata.getAnnotationAttributes(
            EnableHibernateReactiveRepositories::class.java.name,
        ) ?: return

        val basePackages = resolveBasePackages(attributes, importingClassMetadata)

        // 기존에 등록된 auto-config registrar가 있으면 제거
        if (registry.containsBeanDefinition("hibernateReactiveRepositoryRegistrar")) {
            registry.removeBeanDefinition("hibernateReactiveRepositoryRegistrar")
        }

        // 커스텀 패키지로 새 registrar 등록
        val beanDefinition = BeanDefinitionBuilder
            .genericBeanDefinition(HibernateReactiveRepositoryRegistrar::class.java)
            .addConstructorArgValue(basePackages)
            .beanDefinition

        registry.registerBeanDefinition("hibernateReactiveRepositoryRegistrar", beanDefinition)
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
        if (packages.isEmpty()) {
            val className = importingClassMetadata.className
            packages.add(Class.forName(className).packageName)
        }

        return packages.toList()
    }
}
