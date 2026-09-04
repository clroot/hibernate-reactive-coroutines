package io.clroot.hibernate.reactive.blockhound

import io.vertx.core.impl.VertxThread
import reactor.blockhound.BlockHound
import reactor.blockhound.integration.BlockHoundIntegration

/**
 * Registers Vert.x event-loop threads as non-blocking threads for BlockHound.
 *
 * BlockHound only detects blocking calls on threads marked as non-blocking. Its
 * default Reactor integration does not cover the Vert.x event loop used by
 * `transactional {}`.
 */
public class VertxEventLoopBlockHoundIntegration : BlockHoundIntegration {
    override fun applyTo(builder: BlockHound.Builder) {
        builder.nonBlockingThreadPredicate { current ->
            current.or { thread ->
                thread is VertxThread && !thread.isWorker && !thread.permitBlockingCalls()
            }
        }

        // pg-client generates a SCRAM nonce during connection setup, which reads
        // from SecureRandom on the event loop. Allow only that driver operation.
        builder.allowBlockingCallsInside("com.ongres.scram.common.ScramFunctions", "nonce")
    }
}
