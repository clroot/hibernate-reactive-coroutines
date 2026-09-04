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
 * End-to-end test that verifies the embedded Netty reactive web server runs on
 * the starter's Vert.x event loops when `share-event-loops` is enabled.
 *
 * The handler verifies that the request runs on a VertxThread belonging to the
 * Vert.x bean's event loop group and that its database transaction uses the
 * same Vert.x instance.
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
        describe("event loop sharing (share-event-loops=true)") {

            it("runs the HTTP request and database transaction on the same Vert.x event loop pool") {
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
