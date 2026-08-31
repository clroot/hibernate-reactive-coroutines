package io.clroot.hibernate.reactive.blockhound

import io.vertx.core.impl.VertxThread
import reactor.blockhound.BlockHound
import reactor.blockhound.integration.BlockHoundIntegration

/**
 * Vert.x 이벤트 루프 스레드를 BlockHound의 검사 대상으로 등록하는 통합.
 *
 * BlockHound는 "논블로킹으로 표시된 스레드"에서만 블로킹 호출을 잡습니다.
 * Reactor의 기본 통합은 reactor-netty 스레드만 표시하므로, `transactional {}` 블록이 실행되는
 * Vert.x 이벤트 루프는 이 통합 없이는 검사 대상에서 빠집니다.
 *
 * 이 모듈을 테스트 클래스패스에 추가하면 ServiceLoader로 자동 등록되며,
 * `BlockHound.install()` 호출만으로 활성화됩니다:
 *
 * ```kotlin
 * BlockHound.install()
 *
 * // 이후 Vert.x 이벤트 루프에서의 블로킹 호출은 BlockingOperationError를 던진다
 * tx.transactional {
 *     Thread.sleep(100) // BlockingOperationError!
 * }
 * ```
 *
 * JDK 13+에서는 테스트 JVM에 다음 플래그가 필요합니다:
 * `-XX:+AllowRedefinitionToAddDeleteMethods -Djdk.attach.allowAttachSelf=true`
 *
 * 주의사항:
 * - Vert.x 워커 스레드(`executeBlocking`)는 블로킹이 허용되므로 표시하지 않습니다.
 * - [VertxThread.permitBlockingCalls]가 true인 스레드도 제외합니다.
 * - BlockHound는 바이트코드 계측이므로 테스트/개발 환경 전용입니다.
 *   운영 환경 탐지는 Vert.x 내장 blocked-thread checker를 사용하세요.
 */
public class VertxEventLoopBlockHoundIntegration : BlockHoundIntegration {
    override fun applyTo(builder: BlockHound.Builder) {
        builder.nonBlockingThreadPredicate { current ->
            current.or { thread ->
                thread is VertxThread && !thread.isWorker && !thread.permitBlockingCalls()
            }
        }

        // pg-client의 SCRAM 인증은 커넥션 수립 중 이벤트 루프에서 nonce를 생성하며,
        // 이때 SecureRandom이 /dev/urandom을 읽는다(FileInputStream#readBytes).
        // 드라이버 내부의 정당한 일회성 동작이므로 nonce 생성 범위로 좁혀 허용한다.
        builder.allowBlockingCallsInside("com.ongres.scram.common.ScramFunctions", "nonce")
    }
}
