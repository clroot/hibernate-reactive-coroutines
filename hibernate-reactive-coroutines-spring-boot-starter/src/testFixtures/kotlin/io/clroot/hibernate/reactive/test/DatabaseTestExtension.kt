package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.container.PostgreSQLTestContainer
import io.kotest.core.extensions.SpecExtension
import io.kotest.core.spec.Spec

/**
 * Kotest Spec Extension으로 TestContainer 라이프사이클을 관리합니다.
 *
 * 테스트 실행 전 PostgreSQL 컨테이너를 시작하고,
 * Spring DataSource 프로퍼티를 자동으로 설정합니다.
 */
class DatabaseTestExtension : SpecExtension {
    override suspend fun intercept(
        spec: Spec,
        execute: suspend (Spec) -> Unit,
    ) {
        PostgreSQLTestContainer.start()
        PostgreSQLTestContainer.configureSystemProperties()
        try {
            execute(spec)
        } finally {
            // 컨테이너 재사용을 위해 정지하지 않음
        }
    }
}
