package io.clroot.hibernate.reactive.spring.boot.repository

import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.boot.autoconfigure.AutoConfigurationPackages
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * Scans [CoroutineCrudRepository] interfaces and registers them as Spring beans.
 *
 * When [basePackages] is empty, scans the packages registered by `@SpringBootApplication`.
 *
 * @param basePackages Base packages to scan.
 */
public class HibernateReactiveRepositoryRegistrar(
    private val basePackages: List<String> = emptyList(),
) : BeanDefinitionRegistryPostProcessor, ApplicationContextAware {

    private lateinit var applicationContext: ApplicationContext

    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.applicationContext = applicationContext
    }

    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        val packagesToScan = basePackages.ifEmpty {
            AutoConfigurationPackages.get(applicationContext)
        }

        val repositoryInterfaces = findRepositoryInterfaces(packagesToScan)
        val beanNames = HibernateReactiveRepositoryBeanNameGenerator.generate(repositoryInterfaces) { beanName ->
            !registry.isBeanNameInUse(beanName)
        }

        repositoryInterfaces.forEach { repositoryInterface ->
            val beanName = beanNames.getValue(repositoryInterface)
            val beanDefinition = createBeanDefinition(repositoryInterface)

            registry.registerBeanDefinition(beanName, beanDefinition)
        }
    }

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
    }

    private fun findRepositoryInterfaces(basePackages: List<String>): List<Class<*>> {
        val scanner = RepositoryComponentScanner().apply {
            addIncludeFilter(HibernateReactiveRepositoryTypeFilter())
        }

        val classLoader = applicationContext.classLoader
            ?: Thread.currentThread().contextClassLoader

        return basePackages
            .flatMap { basePackage ->
                scanner.findCandidateComponents(basePackage)
                    .mapNotNull { it.beanClassName }
                    .map { classLoader.loadClass(it) }
            }
            .filter { clazz ->
                clazz.isInterface && clazz != CoroutineCrudRepository::class.java
            }
            .distinct()
    }

    private fun createBeanDefinition(repositoryInterface: Class<*>): BeanDefinition {
        return BeanDefinitionBuilder
            .genericBeanDefinition(HibernateReactiveRepositoryFactoryBean::class.java)
            .addConstructorArgValue(repositoryInterface)
            .setScope(BeanDefinition.SCOPE_SINGLETON)
            .beanDefinition
    }

}

internal object HibernateReactiveRepositoryBeanNameGenerator {
    fun generate(
        repositoryInterfaces: List<Class<*>>,
        isNameAvailable: (String) -> Boolean,
    ): Map<Class<*>, String> {
        val conventionalNames = repositoryInterfaces.associateWith { repositoryInterface ->
            repositoryInterface.simpleName.replaceFirstChar { it.lowercase() }
        }
        val duplicateNames = conventionalNames.values
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys

        return repositoryInterfaces.associateWith { repositoryInterface ->
            val conventionalName = conventionalNames.getValue(repositoryInterface)
            val beanName = if (conventionalName in duplicateNames || !isNameAvailable(conventionalName)) {
                repositoryInterface.name
            } else {
                conventionalName
            }

            check(isNameAvailable(beanName)) {
                "Cannot register repository '${repositoryInterface.name}': bean name '$beanName' is already in use"
            }
            beanName
        }
    }
}
