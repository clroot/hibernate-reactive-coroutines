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
 * Vert.x 스레드 종류별로 BlockHound 검사 대상 여부를 검증합니다.
 *
 * - 이벤트 루프 스레드: 블로킹 호출이 탐지되어야 함
 * - 워커 스레드(executeBlocking): 블로킹이 허용되어야 함
 */
class VertxEventLoopBlockHoundIntegrationTest : DescribeSpec({
    BlockHound.install()

    val vertx = Vertx.vertx()

    afterSpec {
        vertx.close().coAwait()
    }

    describe("VertxEventLoopBlockHoundIntegration") {

        it("이벤트 루프 스레드에서 Thread.sleep은 BlockingOperationError를 던진다") {
            val result = CompletableDeferred<Throwable?>()
            vertx.runOnContext {
                result.complete(runCatching { Thread.sleep(10) }.exceptionOrNull())
            }
            result.await().shouldBeInstanceOf<BlockingOperationError>()
        }

        it("워커 스레드(executeBlocking)에서는 블로킹이 허용된다") {
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
