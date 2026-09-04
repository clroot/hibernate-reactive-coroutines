package io.clroot.hibernate.reactive.test

import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.vertx.core.Vertx
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.reactive.vertx.VertxInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * Verifies that the starter backs off for an application-provided Vertx bean
 * and injects that instance into Hibernate Reactive.
 */
@SpringBootTest(classes = [TestApplication::class, UserProvidedVertxTest.UserVertxConfig::class])
class UserProvidedVertxTest : IntegrationTestBase() {

    @TestConfiguration(proxyBeanMethods = false)
    class UserVertxConfig {
        @Bean
        fun customVertx(): Vertx = Vertx.vertx()
    }

    @Autowired
    private lateinit var vertx: Vertx

    @Autowired
    private lateinit var hibernateSessionFactory: org.hibernate.SessionFactory

    init {
        describe("application-provided Vertx bean") {

            it("backs off and injects the application bean into Hibernate Reactive") {
                val vertxInstance = (hibernateSessionFactory as SessionFactoryImplementor)
                    .serviceRegistry
                    .getService(VertxInstance::class.java)!!

                vertxInstance.vertx shouldBeSameInstanceAs vertx
            }
        }
    }
}
