package io.clroot.hibernate.reactive.spring.boot.autoconfigure

import io.netty.channel.EventLoopGroup
import io.vertx.core.Vertx
import io.vertx.core.internal.VertxInternal
import reactor.core.publisher.Mono
import reactor.netty.resources.LoopResources
import java.time.Duration

/**
 * reactor-netty가 Vert.x의 이벤트 루프 그룹을 그대로 사용하게 하는 [LoopResources] 구현.
 *
 * 내장 Netty 리액티브 웹 서버가 이 리소스로 실행되면 HTTP 서빙과 Hibernate Reactive의
 * DB I/O가 같은 스레드 풀(전부 `VertxThread`)에서 동작합니다. 요청이 처음부터 Vert.x
 * 이벤트 루프에서 시작하므로 `transactional {}` 진입 시 다른 풀로 넘어가는 스레드 전환이
 * 사라지고, Vert.x의 blocked-thread checker와 BlockHound 통합이 웹 계층까지 커버합니다.
 *
 * 생명주기: 이벤트 루프의 소유자는 [Vertx] 빈입니다. reactor-netty가 종료 시
 * [dispose]/[disposeLater]를 호출해도 루프를 닫지 않습니다.
 *
 * 참고: 이벤트 루프 그룹 접근에 Vert.x 5의 internal API([VertxInternal])를 사용합니다.
 * Vert.x 5는 공개 API에서 `nettyEventLoopGroup()`을 제거했기 때문이며,
 * 마이너 업그레이드에서 시그니처가 바뀔 수 있습니다.
 */
public class VertxLoopResources(vertx: Vertx) : LoopResources {

    private val eventLoopGroup: EventLoopGroup = run {
        require(vertx is VertxInternal) {
            "Expected a VertxInternal instance to access the Netty event loop group, " +
                "got ${vertx.javaClass.name}"
        }
        vertx.nettyEventLoopGroup()
    }

    override fun onServer(useNative: Boolean): EventLoopGroup = eventLoopGroup

    override fun dispose() {
        // Vert.x 빈이 루프를 소유하므로 웹 계층 종료 시 닫지 않는다.
    }

    override fun disposeLater(quietPeriod: Duration, timeout: Duration): Mono<Void> = Mono.empty()
}
