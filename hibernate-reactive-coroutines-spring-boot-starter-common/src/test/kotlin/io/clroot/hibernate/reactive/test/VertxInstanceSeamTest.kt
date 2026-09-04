package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.vertx.core.Vertx
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.reactive.vertx.VertxInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Verifies that the starter-owned Vert.x instance is injected into Hibernate Reactive.
 *
 * Without this seam, Hibernate Reactive's internal DefaultVertxInstance creates
 * a separate Vert.x event-loop pool outside application control.
 */
@SpringBootTest(classes = [TestApplication::class])
class VertxInstanceSeamTest : IntegrationTestBase() {

    @Autowired
    private lateinit var vertx: Vertx

    @Autowired
    private lateinit var hibernateSessionFactory: org.hibernate.SessionFactory

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("Vert.x instance seam") {

            it("uses Spring's Vertx bean for Hibernate Reactive's VertxInstance service") {
                val vertxInstance = (hibernateSessionFactory as SessionFactoryImplementor)
                    .serviceRegistry
                    .getService(VertxInstance::class.java)!!

                vertxInstance.vertx shouldBeSameInstanceAs vertx
            }

            it("runs transactional blocks in a context owned by Spring's Vertx bean") {
                val owner = tx.readOnly { Vertx.currentContext()?.owner() }

                owner shouldBeSameInstanceAs vertx
            }
        }
    }
}
