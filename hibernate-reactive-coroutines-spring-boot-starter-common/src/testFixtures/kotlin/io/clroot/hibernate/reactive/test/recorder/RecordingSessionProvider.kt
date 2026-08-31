package io.clroot.hibernate.reactive.test.recorder

import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import io.smallrye.mutiny.Uni
import org.hibernate.reactive.mutiny.Mutiny

/**
 * HQL 쿼리를 기록하는 세션 제공자.
 *
 * [TransactionalAwareSessionProvider]를 확장하여 모든 세션 작업에서
 * [RecordingSessionWrapper]를 사용하도록 합니다.
 *
 * 테스트 환경에서만 사용되며, 쿼리 검증이 필요한 테스트에서 활용됩니다.
 */
class RecordingSessionProvider(
    sessionFactory: Mutiny.SessionFactory,
    private val recorder: HqlRecorder,
) : TransactionalAwareSessionProvider(sessionFactory) {

    override suspend fun <T> read(block: (Mutiny.Session) -> Uni<T>): T {
        return super.read { session ->
            block(RecordingSessionWrapper(session, recorder))
        }
    }

    override suspend fun <T> write(block: (Mutiny.Session) -> Uni<T>): T {
        return super.write { session ->
            block(RecordingSessionWrapper(session, recorder))
        }
    }
}
