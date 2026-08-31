package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.kotest.matchers.shouldBe
import io.vertx.core.Vertx
import io.vertx.core.impl.VertxThread
import io.vertx.core.internal.VertxInternal
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * share-event-loops opt-in이 켜지면 내장 Netty 리액티브 웹 서버가
 * 스타터의 Vert.x 이벤트 루프 위에서 실행되는지 검증하는 E2E 테스트.
 *
 * 핸들러에서 세 가지를 확인합니다:
 * 1. 요청 처리 스레드가 VertxThread다 (reactor-netty 자체 풀이 아님)
 * 2. 그 스레드가 Vertx 빈의 이벤트 루프 그룹 소속이다
 * 3. 같은 요청 안의 DB 트랜잭션도 같은 Vertx 인스턴스에서 실행된다
 */
@SpringBootTest(
    classes = [TestApplication::class, VertxEventLoopSharingIntegrationTest.ThreadInfoRoutes::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.main.web-application-type=reactive",
        "spring.jpa.properties.hibernate.reactive.vertx.share-event-loops=true",
    ],
)
class VertxEventLoopSharingIntegrationTest : IntegrationTestBase() {

    @TestConfiguration(proxyBeanMethods = false)
    class ThreadInfoRoutes {
        @Bean
        fun threadInfoRoute(
            vertx: Vertx,
            tx: ReactiveTransactionExecutor,
        ): RouterFunction<ServerResponse> = coRouter {
            GET("/thread-info") {
                val thread = Thread.currentThread()
                val inVertxGroup = (vertx as VertxInternal).nettyEventLoopGroup()
                    .any { executor -> executor.inEventLoop(thread) }
                val dbVertx = tx.readOnly { Vertx.currentContext()?.owner() }

                ServerResponse.ok().bodyValueAndAwait(
                    "${thread is VertxThread} $inVertxGroup ${dbVertx === vertx}",
                )
            }
        }
    }

    @Autowired
    private lateinit var environment: Environment

    init {
        describe("이벤트 루프 공유 (share-event-loops=true)") {

            it("HTTP 요청과 DB 트랜잭션이 같은 Vert.x 이벤트 루프 풀에서 실행된다") {
                val port = environment.getProperty("local.server.port")
                    ?: error("local.server.port is not set — the web server did not start")

                val response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:$port/thread-info")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                response.statusCode() shouldBe 200
                response.body() shouldBe "true true true"
            }
        }
    }
}
