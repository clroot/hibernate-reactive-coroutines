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
 * CoroutineCrudRepository 인터페이스를 스캔하고 Spring Bean으로 등록하는 PostProcessor.
 *
 * @SpringBootApplication 패키지 기준으로 CoroutineCrudRepository를 구현한 인터페이스를 찾아
 * 각각에 대해 [HibernateReactiveRepositoryFactoryBean]을 Bean으로 등록합니다.
 *
 * @param basePackages 스캔할 베이스 패키지 (비어있으면 @SpringBootApplication 패키지 사용)
 */
class HibernateReactiveRepositoryRegistrar(
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

        repositoryInterfaces.forEach { repositoryInterface ->
            val beanName = generateBeanName(repositoryInterface)
            val beanDefinition = createBeanDefinition(repositoryInterface)

            registry.registerBeanDefinition(beanName, beanDefinition)
        }
    }

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        // Bean 정의 단계에서 처리 완료, 추가 작업 없음
    }

    /**
     * CoroutineCrudRepository를 구현한 인터페이스들을 스캔합니다.
     */
    private fun findRepositoryInterfaces(basePackages: List<String>): List<Class<*>> {
        // 인터페이스도 스캔하는 커스텀 스캐너
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
                // CoroutineCrudRepository 자체는 제외
                clazz.isInterface && clazz != CoroutineCrudRepository::class.java
            }
            .distinct()
    }

    /**
     * Repository 인터페이스에 대한 FactoryBean 정의를 생성합니다.
     */
    private fun createBeanDefinition(repositoryInterface: Class<*>): BeanDefinition {
        return BeanDefinitionBuilder
            .genericBeanDefinition(HibernateReactiveRepositoryFactoryBean::class.java)
            .addConstructorArgValue(repositoryInterface)
            .setScope(BeanDefinition.SCOPE_SINGLETON)
            .beanDefinition
    }

    /**
     * Bean 이름을 생성합니다. (예: UserRepository → userRepository)
     */
    private fun generateBeanName(repositoryInterface: Class<*>): String {
        val simpleName = repositoryInterface.simpleName
        return simpleName.replaceFirstChar { it.lowercase() }
    }
}
