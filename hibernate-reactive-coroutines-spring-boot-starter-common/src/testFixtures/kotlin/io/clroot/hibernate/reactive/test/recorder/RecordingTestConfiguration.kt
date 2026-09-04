package io.clroot.hibernate.reactive.test.recorder

import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import org.hibernate.reactive.mutiny.Mutiny
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * Test configuration for HQL recording.
 *
 * The primary provider wraps sessions so test queries can be asserted.
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
