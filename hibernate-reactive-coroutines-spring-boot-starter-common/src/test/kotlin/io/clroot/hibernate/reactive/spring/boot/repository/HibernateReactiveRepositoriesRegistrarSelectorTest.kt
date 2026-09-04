package io.clroot.hibernate.reactive.spring.boot.repository

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.core.type.AnnotationMetadata

class HibernateReactiveRepositoriesRegistrarSelectorTest : DescribeSpec({

    describe("HibernateReactiveRepositoriesRegistrarSelector") {

        context("registerBeanDefinitions") {

            it("registers a registrar for the specified base packages") {
                val selector = HibernateReactiveRepositoriesRegistrarSelector()
                val metadata = mockk<AnnotationMetadata>()
                val registry = mockk<BeanDefinitionRegistry>(relaxed = true)

                every { metadata.getAnnotationAttributes(EnableHibernateReactiveRepositories::class.java.name) } returns mapOf(
                    "basePackages" to arrayOf("com.example.repo"),
                    "basePackageClasses" to emptyArray<Class<*>>(),
                )
                every { registry.containsBeanDefinition("hibernateReactiveRepositoryRegistrar") } returns false

                val beanDefSlot = slot<BeanDefinition>()

                selector.registerBeanDefinitions(metadata, registry)

                verify { registry.registerBeanDefinition("hibernateReactiveRepositoryRegistrar", capture(beanDefSlot)) }
                beanDefSlot.captured.beanClassName shouldBe HibernateReactiveRepositoryRegistrar::class.java.name
            }

            it("uses the packages of specified base package classes") {
                val selector = HibernateReactiveRepositoriesRegistrarSelector()
                val metadata = mockk<AnnotationMetadata>()
                val registry = mockk<BeanDefinitionRegistry>(relaxed = true)

                every { metadata.getAnnotationAttributes(EnableHibernateReactiveRepositories::class.java.name) } returns mapOf(
                    "basePackages" to emptyArray<String>(),
                    "basePackageClasses" to arrayOf(MarkerClass::class.java),
                )
                every { registry.containsBeanDefinition("hibernateReactiveRepositoryRegistrar") } returns false

                selector.registerBeanDefinitions(metadata, registry)

                verify { registry.registerBeanDefinition(eq("hibernateReactiveRepositoryRegistrar"), any()) }
            }

            it("uses the annotated class package when no base package is specified") {
                val selector = HibernateReactiveRepositoriesRegistrarSelector()
                val metadata = mockk<AnnotationMetadata>()
                val registry = mockk<BeanDefinitionRegistry>(relaxed = true)

                every { metadata.getAnnotationAttributes(EnableHibernateReactiveRepositories::class.java.name) } returns mapOf(
                    "basePackages" to emptyArray<String>(),
                    "basePackageClasses" to emptyArray<Class<*>>(),
                )
                // Use a loadable class so the selector can resolve its package.
                every { metadata.className } returns HibernateReactiveRepositoriesRegistrarSelectorTest::class.java.name
                every { registry.containsBeanDefinition("hibernateReactiveRepositoryRegistrar") } returns false

                selector.registerBeanDefinitions(metadata, registry)

                verify { registry.registerBeanDefinition(eq("hibernateReactiveRepositoryRegistrar"), any()) }
            }

            it("replaces an existing registrar") {
                val selector = HibernateReactiveRepositoriesRegistrarSelector()
                val metadata = mockk<AnnotationMetadata>()
                val registry = mockk<BeanDefinitionRegistry>(relaxed = true)

                every { metadata.getAnnotationAttributes(EnableHibernateReactiveRepositories::class.java.name) } returns mapOf(
                    "basePackages" to arrayOf("com.example.custom"),
                    "basePackageClasses" to emptyArray<Class<*>>(),
                )
                every { registry.containsBeanDefinition("hibernateReactiveRepositoryRegistrar") } returns true

                selector.registerBeanDefinitions(metadata, registry)

                verify { registry.removeBeanDefinition("hibernateReactiveRepositoryRegistrar") }
                verify { registry.registerBeanDefinition(eq("hibernateReactiveRepositoryRegistrar"), any()) }
            }

            it("does not register a bean definition when annotation attributes are absent") {
                val selector = HibernateReactiveRepositoriesRegistrarSelector()
                val metadata = mockk<AnnotationMetadata>()
                val registry = mockk<BeanDefinitionRegistry>(relaxed = true)

                every { metadata.getAnnotationAttributes(EnableHibernateReactiveRepositories::class.java.name) } returns null

                selector.registerBeanDefinitions(metadata, registry)

                verify(exactly = 0) { registry.registerBeanDefinition(any(), any()) }
            }
        }
    }
}) {
    companion object {
        class MarkerClass
    }
}
