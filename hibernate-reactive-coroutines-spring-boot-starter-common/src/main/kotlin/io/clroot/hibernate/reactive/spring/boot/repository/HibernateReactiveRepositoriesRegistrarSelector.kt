package io.clroot.hibernate.reactive.spring.boot.repository

import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.type.AnnotationMetadata

/**
 * Registers the repository registrar requested by [EnableHibernateReactiveRepositories].
 */
internal class HibernateReactiveRepositoriesRegistrarSelector : ImportBeanDefinitionRegistrar {

    override fun registerBeanDefinitions(
        importingClassMetadata: AnnotationMetadata,
        registry: BeanDefinitionRegistry,
    ) {
        val attributes = importingClassMetadata.getAnnotationAttributes(
            EnableHibernateReactiveRepositories::class.java.name,
        ) ?: return

        val declaredPackages = resolveBasePackages(attributes, importingClassMetadata)

        // Merge declarations so a later configuration class does not discard repositories from earlier modules.
        val basePackages = (previouslyDeclaredPackages(registry) + declaredPackages).distinct()

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
     * Returns packages registered by earlier `@EnableHibernateReactiveRepositories` declarations.
     *
     * The auto-configured registrar has no declaration attribute, allowing an explicit declaration to replace
     * the default scan.
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

        @Suppress("UNCHECKED_CAST")
        val basePackagesAttr = attributes["basePackages"] as? Array<String> ?: emptyArray()
        packages.addAll(basePackagesAttr)

        @Suppress("UNCHECKED_CAST")
        val basePackageClasses = attributes["basePackageClasses"] as? Array<Class<*>> ?: emptyArray()
        basePackageClasses.forEach { clazz ->
            packages.add(clazz.packageName)
        }

        // Read the package from metadata to avoid loading the application class with the wrong class loader.
        if (packages.isEmpty()) {
            packages.add(importingClassMetadata.className.substringBeforeLast('.', ""))
        }

        return packages.toList()
    }

    private companion object {
        private const val REGISTRAR_BEAN_NAME = "hibernateReactiveRepositoryRegistrar"

        /** Identifies selector registrations and stores their accumulated packages. */
        private const val DECLARED_PACKAGES_ATTRIBUTE =
            "io.clroot.hibernate.reactive.declaredBasePackages"
    }
}
