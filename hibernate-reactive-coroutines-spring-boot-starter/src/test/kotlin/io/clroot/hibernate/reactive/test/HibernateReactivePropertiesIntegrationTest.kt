package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.spring.boot.autoconfigure.HibernateReactiveProperties
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(classes = [TestApplication::class])
class HibernateReactivePropertiesIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var properties: HibernateReactiveProperties

    init {
        describe("Hibernate Reactive configuration properties") {
            it("binds the pool size from the documented prefix") {
                properties.poolSize shouldBe 5
            }
        }
    }
}
