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
 * Repository Bean 등록 테스트 - 단일 패키지 스캔
 *
 * basePackages로 특정 패키지만 스캔하도록 설정했을 때,
 * 해당 패키지의 Repository만 빈으로 등록되는지 검증합니다.
 */
@SpringBootTest(classes = [SinglePackageTestConfig::class])
class SinglePackageScanTest : IntegrationTestBase() {

    @Autowired
    private lateinit var context: ApplicationContext

    init {
        describe("basePackages로 단일 패키지만 스캔") {
            context("pkg1만 스캔하도록 설정된 경우") {
                it("pkg1의 Repository는 빈으로 등록된다") {
                    val bean = context.getBean(Package1Repository::class.java)
                    bean.shouldNotBeNull()
                }

                it("pkg2의 Repository는 빈으로 등록되지 않는다") {
                    shouldThrow<NoSuchBeanDefinitionException> {
                        context.getBean(Package2Repository::class.java)
                    }
                }
            }
        }
    }
}

/**
 * Repository Bean 등록 테스트 - 여러 패키지 스캔
 *
 * basePackages로 여러 패키지를 지정했을 때,
 * 모든 지정된 패키지의 Repository가 빈으로 등록되는지 검증합니다.
 */
@SpringBootTest(classes = [MultiPackageTestConfig::class])
class MultiPackageScanTest : IntegrationTestBase() {

    @Autowired
    private lateinit var context: ApplicationContext

    init {
        describe("basePackages로 여러 패키지 스캔") {
            context("pkg1과 pkg2 모두 스캔하도록 설정된 경우") {
                it("pkg1의 Repository가 빈으로 등록된다") {
                    val bean = context.getBean(Package1Repository::class.java)
                    bean.shouldNotBeNull()
                }

                it("pkg2의 Repository가 빈으로 등록된다") {
                    val bean = context.getBean(Package2Repository::class.java)
                    bean.shouldNotBeNull()
                }
            }
        }
    }
}

/**
 * Repository Bean 등록 테스트 - basePackageClasses 사용
 *
 * basePackageClasses로 마커 클래스를 지정했을 때,
 * 해당 클래스가 위치한 패키지의 Repository만 빈으로 등록되는지 검증합니다.
 */
@SpringBootTest(classes = [BasePackageClassesTestConfig::class])
class BasePackageClassesScanTest : IntegrationTestBase() {

    @Autowired
    private lateinit var context: ApplicationContext

    init {
        describe("basePackageClasses로 패키지 스캔") {
            context("Package1Repository 클래스를 마커로 지정한 경우") {
                it("해당 클래스가 위치한 패키지의 Repository가 빈으로 등록된다") {
                    val bean = context.getBean(Package1Repository::class.java)
                    bean.shouldNotBeNull()
                }

                it("다른 패키지의 Repository는 빈으로 등록되지 않는다") {
                    shouldThrow<NoSuchBeanDefinitionException> {
                        context.getBean(Package2Repository::class.java)
                    }
                }
            }
        }
    }
}

// Test Configurations
// @SpringBootApplication 대신 @Configuration + @EnableAutoConfiguration 사용
// 컴포넌트 스캔을 하지 않아 service 패키지의 빈이 등록되지 않음

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
