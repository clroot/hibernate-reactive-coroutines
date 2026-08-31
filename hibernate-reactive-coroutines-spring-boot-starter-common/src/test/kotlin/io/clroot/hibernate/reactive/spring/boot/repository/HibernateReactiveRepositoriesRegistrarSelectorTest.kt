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

            it("basePackages가 지정되면 해당 패키지로 Registrar를 등록한다") {
                // given
                val selector = HibernateReactiveRepositoriesRegistrarSelector()
                val metadata = mockk<AnnotationMetadata>()
                val registry = mockk<BeanDefinitionRegistry>(relaxed = true)

                every { metadata.getAnnotationAttributes(EnableHibernateReactiveRepositories::class.java.name) } returns mapOf(
                    "basePackages" to arrayOf("com.example.repo"),
                    "basePackageClasses" to emptyArray<Class<*>>(),
                )
                every { registry.containsBeanDefinition("hibernateReactiveRepositoryRegistrar") } returns false

                val beanDefSlot = slot<BeanDefinition>()

                // when
                selector.registerBeanDefinitions(metadata, registry)

                // then
                verify { registry.registerBeanDefinition("hibernateReactiveRepositoryRegistrar", capture(beanDefSlot)) }
                beanDefSlot.captured.beanClassName shouldBe HibernateReactiveRepositoryRegistrar::class.java.name
            }

            it("basePackageClasses가 지정되면 해당 클래스의 패키지를 사용한다") {
                // given
                val selector = HibernateReactiveRepositoriesRegistrarSelector()
                val metadata = mockk<AnnotationMetadata>()
                val registry = mockk<BeanDefinitionRegistry>(relaxed = true)

                every { metadata.getAnnotationAttributes(EnableHibernateReactiveRepositories::class.java.name) } returns mapOf(
                    "basePackages" to emptyArray<String>(),
                    "basePackageClasses" to arrayOf(MarkerClass::class.java),
                )
                every { registry.containsBeanDefinition("hibernateReactiveRepositoryRegistrar") } returns false

                // when
                selector.registerBeanDefinitions(metadata, registry)

                // then
                verify { registry.registerBeanDefinition(eq("hibernateReactiveRepositoryRegistrar"), any()) }
            }

            it("아무 속성도 지정되지 않으면 어노테이션이 붙은 클래스의 패키지를 사용한다") {
                // given
                val selector = HibernateReactiveRepositoriesRegistrarSelector()
                val metadata = mockk<AnnotationMetadata>()
                val registry = mockk<BeanDefinitionRegistry>(relaxed = true)

                every { metadata.getAnnotationAttributes(EnableHibernateReactiveRepositories::class.java.name) } returns mapOf(
                    "basePackages" to emptyArray<String>(),
                    "basePackageClasses" to emptyArray<Class<*>>(),
                )
                // 실제 존재하는 클래스 사용
                every { metadata.className } returns HibernateReactiveRepositoriesRegistrarSelectorTest::class.java.name
                every { registry.containsBeanDefinition("hibernateReactiveRepositoryRegistrar") } returns false

                // when
                selector.registerBeanDefinitions(metadata, registry)

                // then
                verify { registry.registerBeanDefinition(eq("hibernateReactiveRepositoryRegistrar"), any()) }
            }

            it("기존에 등록된 registrar가 있으면 제거 후 새로 등록한다") {
                // given
                val selector = HibernateReactiveRepositoriesRegistrarSelector()
                val metadata = mockk<AnnotationMetadata>()
                val registry = mockk<BeanDefinitionRegistry>(relaxed = true)

                every { metadata.getAnnotationAttributes(EnableHibernateReactiveRepositories::class.java.name) } returns mapOf(
                    "basePackages" to arrayOf("com.example.custom"),
                    "basePackageClasses" to emptyArray<Class<*>>(),
                )
                every { registry.containsBeanDefinition("hibernateReactiveRepositoryRegistrar") } returns true

                // when
                selector.registerBeanDefinitions(metadata, registry)

                // then
                verify { registry.removeBeanDefinition("hibernateReactiveRepositoryRegistrar") }
                verify { registry.registerBeanDefinition(eq("hibernateReactiveRepositoryRegistrar"), any()) }
            }

            it("어노테이션 속성이 없으면 아무것도 등록하지 않는다") {
                // given
                val selector = HibernateReactiveRepositoriesRegistrarSelector()
                val metadata = mockk<AnnotationMetadata>()
                val registry = mockk<BeanDefinitionRegistry>(relaxed = true)

                every { metadata.getAnnotationAttributes(EnableHibernateReactiveRepositories::class.java.name) } returns null

                // when
                selector.registerBeanDefinitions(metadata, registry)

                // then
                verify(exactly = 0) { registry.registerBeanDefinition(any(), any()) }
            }
        }
    }
}) {
    companion object {
        class MarkerClass
    }
}
