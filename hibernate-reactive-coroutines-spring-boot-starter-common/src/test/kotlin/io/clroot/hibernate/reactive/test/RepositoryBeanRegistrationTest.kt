package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.spring.boot.autoconfigure.HibernateReactiveAutoConfiguration
import io.clroot.hibernate.reactive.spring.boot.repository.EnableHibernateReactiveRepositories
import io.clroot.hibernate.reactive.test.isolated.pkg1.Package1Repository
import io.clroot.hibernate.reactive.test.isolated.pkg2.Package2Repository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 * Repository bean registration test for a single package scan.
 *
 * Verifies that configuring basePackages to scan one package registers only
 * repositories in that package.
 */
@SpringBootTest(classes = [SinglePackageTestConfig::class])
class SinglePackageScanTest : IntegrationTestBase() {

    @Autowired
    private lateinit var context: ApplicationContext

    init {
        describe("scanning a single package with basePackages") {
            context("when only pkg1 is configured for scanning") {
                it("registers the pkg1 repository as a bean") {
                    val bean = context.getBean(Package1Repository::class.java)
                    bean.shouldNotBeNull()
                }

                it("does not register the pkg2 repository as a bean") {
                    shouldThrow<NoSuchBeanDefinitionException> {
                        context.getBean(Package2Repository::class.java)
                    }
                }
            }
        }
    }
}

/**
 * Repository bean registration test for multiple package scans.
 *
 * Verifies that configuring basePackages with multiple packages registers
 * repositories from each package.
 */
@SpringBootTest(classes = [MultiPackageTestConfig::class])
class MultiPackageScanTest : IntegrationTestBase() {

    @Autowired
    private lateinit var context: ApplicationContext

    init {
        describe("scanning multiple packages with basePackages") {
            context("when pkg1 and pkg2 are configured for scanning") {
                it("registers the pkg1 repository as a bean") {
                    val bean = context.getBean(Package1Repository::class.java)
                    bean.shouldNotBeNull()
                }

                it("registers the pkg2 repository as a bean") {
                    val bean = context.getBean(Package2Repository::class.java)
                    bean.shouldNotBeNull()
                }
            }
        }
    }
}

/**
 * Repository bean registration test using basePackageClasses.
 *
 * Verifies that a marker class in basePackageClasses limits registration to
 * repositories in its package.
 */
@SpringBootTest(classes = [BasePackageClassesTestConfig::class])
class BasePackageClassesScanTest : IntegrationTestBase() {

    @Autowired
    private lateinit var context: ApplicationContext

    init {
        describe("scanning a package with basePackageClasses") {
            context("when Package1Repository is configured as a marker class") {
                it("registers repositories in the marker class package as beans") {
                    val bean = context.getBean(Package1Repository::class.java)
                    bean.shouldNotBeNull()
                }

                it("does not register repositories from other packages as beans") {
                    shouldThrow<NoSuchBeanDefinitionException> {
                        context.getBean(Package2Repository::class.java)
                    }
                }
            }
        }
    }
}

// These configurations avoid component scanning, so service-package beans are not registered.

@Configuration
@EnableAutoConfiguration
@Import(HibernateReactiveAutoConfiguration::class)
@EnableHibernateReactiveRepositories(
    basePackages = ["io.clroot.hibernate.reactive.test.isolated.pkg1"],
)
class SinglePackageTestConfig

@Configuration
@EnableAutoConfiguration
@Import(HibernateReactiveAutoConfiguration::class)
@EnableHibernateReactiveRepositories(
    basePackages = [
        "io.clroot.hibernate.reactive.test.isolated.pkg1",
        "io.clroot.hibernate.reactive.test.isolated.pkg2",
    ],
)
class MultiPackageTestConfig

@Configuration
@EnableAutoConfiguration
@Import(HibernateReactiveAutoConfiguration::class)
@EnableHibernateReactiveRepositories(
    basePackageClasses = [Package1Repository::class],
)
class BasePackageClassesTestConfig
