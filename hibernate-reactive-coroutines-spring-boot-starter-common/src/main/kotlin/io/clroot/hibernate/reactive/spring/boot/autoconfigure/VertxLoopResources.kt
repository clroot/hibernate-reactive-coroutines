package io.clroot.hibernate.reactive.spring.boot.autoconfigure

import io.netty.channel.EventLoopGroup
import io.vertx.core.Vertx
import io.vertx.core.internal.VertxInternal
import reactor.core.publisher.Mono
import reactor.netty.resources.LoopResources
import java.time.Duration

/**
 * [LoopResources] implementation that exposes Vert.x's event-loop group to reactor-netty.
 *
 * HTTP serving and Hibernate Reactive database I/O then share `VertxThread`s. Requests begin on
 * a Vert.x event loop, avoiding a pool switch when entering `transactional {}` and extending the
 * Vert.x blocked-thread checker and BlockHound integration to the web layer.
 *
 * The [Vertx] bean owns the event loops. [dispose] and [disposeLater] intentionally do not close
 * them when reactor-netty shuts down.
 *
 * This uses Vert.x 5's internal [VertxInternal] API because its public API no longer exposes
 * `nettyEventLoopGroup()`. Minor Vert.x upgrades may change that internal signature.
 */
internal class VertxLoopResources(vertx: Vertx) : LoopResources {

    private val eventLoopGroup: EventLoopGroup = run {
        require(vertx is VertxInternal) {
            "Expected a VertxInternal instance to access the Netty event loop group, " +
                "got ${vertx.javaClass.name}"
        }
        vertx.nettyEventLoopGroup()
    }

    override fun onServer(useNative: Boolean): EventLoopGroup = eventLoopGroup

    override fun dispose() {
        // The Vert.x bean owns the loops.
    }

    override fun disposeLater(quietPeriod: Duration, timeout: Duration): Mono<Void> = Mono.empty()
}
