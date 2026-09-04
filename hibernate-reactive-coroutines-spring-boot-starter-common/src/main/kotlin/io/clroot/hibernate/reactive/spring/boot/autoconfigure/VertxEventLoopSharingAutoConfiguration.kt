package io.clroot.hibernate.reactive.spring.boot.autoconfigure

import io.vertx.core.Vertx
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.http.client.ReactorResourceFactory
import reactor.netty.resources.LoopResources

/**
 * Opt-in auto-configuration that runs the embedded reactive Netty web server on Hibernate
 * Reactive's Vert.x event loops.
 *
 * Enabled only by `spring.jpa.properties.hibernate.reactive.vertx.share-event-loops=true`.
 * It supplies the `reactorResourceFactory` before Spring Boot does, so the embedded server uses
 * [VertxLoopResources] instead of creating a separate reactor-netty pool.
 *
 * Sharing reduces threads and cross-pool context switches, but weakens fault isolation: a
 * blocking call in `transactional {}` can stall every HTTP connection assigned to that loop,
 * including health checks.
 *
 * Verify blocking-call safety with `hibernate-reactive-coroutines-blockhound`; monitor
 * production with conservative blocked-thread checker thresholds such as
 * `hibernate.reactive.vertx.max-event-loop-execute-time`.
 *
 * String-based references to the Boot 3.x and 4.x server auto-configurations keep this class
 * compatible with both platforms.
 */
@AutoConfiguration(
    after = [HibernateReactiveAutoConfiguration::class],
    beforeName = [
        // Boot 3.x consumes the reactorResourceFactory bean from EmbeddedNetty.
        "org.springframework.boot.autoconfigure.web.reactive.ReactiveWebServerFactoryAutoConfiguration",
        // Boot 4.x retains this wiring in the spring-boot-reactor-netty module.
        "org.springframework.boot.reactor.netty.autoconfigure.NettyReactiveWebServerAutoConfiguration",
    ],
)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(ReactorResourceFactory::class, LoopResources::class)
@ConditionalOnProperty(
    prefix = "spring.jpa.properties.hibernate.reactive.vertx",
    name = ["share-event-loops"],
    havingValue = "true",
)
public class VertxEventLoopSharingAutoConfiguration {

    /**
     * [ReactorResourceFactory] used by the embedded server.
     *
     * Registering it before Boot's factory makes the server use Vert.x loops. It backs off for an
     * application-provided [ReactorResourceFactory].
     */
    @Bean
    @ConditionalOnMissingBean(ReactorResourceFactory::class)
    public fun reactorResourceFactory(vertx: Vertx): ReactorResourceFactory =
        ReactorResourceFactory().apply {
            isUseGlobalResources = false
            setLoopResources(VertxLoopResources(vertx))
        }
}
