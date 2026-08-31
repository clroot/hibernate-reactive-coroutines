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
 * 애플리케이션이 직접 정의한 Vertx 빈이 있으면 스타터가 물러나고
 * 그 빈을 Hibernate Reactive에 주입하는지 검증합니다.
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
        describe("사용자 정의 Vertx 빈") {

            it("스타터의 vertx 빈은 물러나고 사용자 빈이 Hibernate Reactive에 주입된다") {
                val vertxInstance = (hibernateSessionFactory as SessionFactoryImplementor)
                    .serviceRegistry
                    .getService(VertxInstance::class.java)!!

                vertxInstance.vertx shouldBeSameInstanceAs vertx
            }
        }
    }
}
