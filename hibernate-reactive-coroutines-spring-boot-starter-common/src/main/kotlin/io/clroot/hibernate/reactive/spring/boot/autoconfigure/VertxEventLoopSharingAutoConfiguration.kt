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
 * 내장 Netty 리액티브 웹 서버를 Hibernate Reactive의 Vert.x 이벤트 루프 위에서 실행하는
 * opt-in auto-configuration.
 *
 * `spring.jpa.properties.hibernate.reactive.vertx.share-event-loops=true`일 때만 활성화되며,
 * Spring Boot의 `reactorResourceFactory` 빈(@ConditionalOnMissingBean)보다 먼저 등록되어
 * 내장 서버가 [VertxLoopResources]를 사용하게 만듭니다. reactor-netty가 자체 이벤트 루프
 * 풀을 만들지 않으므로 앱 전체(HTTP 서빙 + DB I/O)가 하나의 스레드 풀을 공유합니다.
 *
 * 트레이드오프 — 켜기 전에 반드시 이해하세요:
 * - **이득**: 스레드 수 절감, 크로스-풀 컨텍스트 스위치 제거. `transactional {}` 진입이
 *   같은 풀 안의 이동이 되고, 요청과 DB 커넥션이 같은 루프에 배정되면 스레드 전환이
 *   완전히 사라집니다.
 * - **비용(장애 격리 약화)**: 트랜잭션 블록 안의 블로킹 호출 하나가 그 루프에 배정된
 *   모든 HTTP 커넥션(헬스체크 포함)을 함께 멈춥니다. 별도 풀 구조에서는 DB 계층에
 *   갇히던 사고의 폭발 반경이 서버 전체로 넓어집니다.
 *
 * 안전장치와 함께 사용하세요:
 * - 테스트: `hibernate-reactive-coroutines-blockhound`로 블로킹 호출 부재를 검증
 * - 운영: `hibernate.reactive.vertx.max-event-loop-execute-time` 등 blocked-thread checker
 *   임계값을 낮춰 감시
 *
 * Boot 3.x의 `ReactiveWebServerFactoryAutoConfiguration`과 Boot 4.x의
 * `NettyReactiveWebServerAutoConfiguration`을 문자열 이름으로 참조해 두 플랫폼에서
 * 하나의 클래스로 동작합니다.
 */
@AutoConfiguration(
    after = [HibernateReactiveAutoConfiguration::class],
    beforeName = [
        // Boot 3.x: EmbeddedNetty가 reactorResourceFactory 빈을 소비한다.
        "org.springframework.boot.autoconfigure.web.reactive.ReactiveWebServerFactoryAutoConfiguration",
        // Boot 4.x: spring-boot-reactor-netty 모듈로 이동한 동일 배선.
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
     * 내장 서버가 사용할 [ReactorResourceFactory].
     *
     * Spring Boot의 기본 `reactorResourceFactory`와 같은 이름·타입으로 먼저 등록되어
     * 기본 빈이 물러나게 합니다. 애플리케이션이 직접 정의한 [ReactorResourceFactory]가
     * 있으면 이 빈도 물러납니다.
     */
    @Bean
    @ConditionalOnMissingBean(ReactorResourceFactory::class)
    public fun reactorResourceFactory(vertx: Vertx): ReactorResourceFactory =
        ReactorResourceFactory().apply {
            isUseGlobalResources = false
            setLoopResources(VertxLoopResources(vertx))
        }
}
