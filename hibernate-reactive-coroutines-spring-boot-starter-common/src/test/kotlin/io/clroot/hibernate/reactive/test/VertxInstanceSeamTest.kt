package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.vertx.core.Vertx
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.reactive.vertx.VertxInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * 스타터가 소유한 Vert.x 인스턴스가 Hibernate Reactive에 주입되는지 검증합니다.
 *
 * 이 seam이 없으면 Hibernate Reactive 내부의 DefaultVertxInstance가
 * 애플리케이션 모르게 별도의 Vert.x(이벤트 루프 풀)를 하나 더 띄웁니다.
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
        describe("Vertx 인스턴스 seam") {

            it("Hibernate Reactive의 VertxInstance 서비스는 Spring의 Vertx 빈이다") {
                val vertxInstance = (hibernateSessionFactory as SessionFactoryImplementor)
                    .serviceRegistry
                    .getService(VertxInstance::class.java)!!

                vertxInstance.vertx shouldBeSameInstanceAs vertx
            }

            it("transactional 블록은 Spring의 Vertx 빈이 소유한 컨텍스트에서 실행된다") {
                val owner = tx.readOnly { Vertx.currentContext()?.owner() }

                owner shouldBeSameInstanceAs vertx
            }
        }
    }
}
