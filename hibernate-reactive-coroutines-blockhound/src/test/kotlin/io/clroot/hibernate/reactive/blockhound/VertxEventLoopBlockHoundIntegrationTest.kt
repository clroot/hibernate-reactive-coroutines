package io.clroot.hibernate.reactive.blockhound

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.CompletableDeferred
import reactor.blockhound.BlockHound
import reactor.blockhound.BlockingOperationError
import java.util.concurrent.Callable

/**
 * Verifies which Vert.x thread types BlockHound monitors.
 *
 * Event-loop threads must reject blocking calls, while `executeBlocking` worker
 * threads permit them.
 */
class VertxEventLoopBlockHoundIntegrationTest : DescribeSpec({
    BlockHound.install()

    val vertx = Vertx.vertx()

    afterSpec {
        vertx.close().coAwait()
    }

    describe("VertxEventLoopBlockHoundIntegration") {

        it("detects Thread.sleep on an event-loop thread") {
            val result = CompletableDeferred<Throwable?>()
            vertx.runOnContext {
                result.complete(runCatching { Thread.sleep(10) }.exceptionOrNull())
            }
            result.await().shouldBeInstanceOf<BlockingOperationError>()
        }

        it("allows Thread.sleep on an executeBlocking worker thread") {
            val result = vertx.executeBlocking(
                Callable {
                    Thread.sleep(10)
                    "ok"
                },
            ).coAwait()

            result shouldBe "ok"
        }
    }
})
