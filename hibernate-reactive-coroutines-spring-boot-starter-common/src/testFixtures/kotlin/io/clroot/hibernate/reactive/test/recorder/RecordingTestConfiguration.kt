package io.clroot.hibernate.reactive.test.recorder

import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import org.hibernate.reactive.mutiny.Mutiny
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * HQL 기록 기능을 위한 테스트 설정.
 *
 * [HqlRecorder]와 [RecordingSessionProvider]를 Bean으로 등록합니다.
 * `@Primary`를 사용하여 기존 [TransactionalAwareSessionProvider]를 대체합니다.
 *
 * 사용 예:
 * ```kotlin
 * @SpringBootTest
 * @Import(RecordingTestConfiguration::class)
 * class MyTest : IntegrationTestBase() {
 *     @Autowired
 *     lateinit var hqlRecorder: HqlRecorder
 * }
 * ```
 */
@TestConfiguration
class RecordingTestConfiguration {

    @Bean
    fun hqlRecorder(): HqlRecorder = HqlRecorder()

    @Bean
    @Primary
    fun recordingSessionProvider(
        sessionFactory: Mutiny.SessionFactory,
        recorder: HqlRecorder,
    ): TransactionalAwareSessionProvider = RecordingSessionProvider(sessionFactory, recorder)
}
