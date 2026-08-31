package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.container.PostgreSQLTestContainer
import io.kotest.core.extensions.SpecExtension
import io.kotest.core.spec.Spec

/**
 * Kotest Spec Extension으로 TestContainer 라이프사이클과 스키마 격리를 관리합니다.
 *
 * 각 Spec(테스트 클래스)마다 고유한 PostgreSQL 스키마를 생성하여
 * 병렬 테스트 실행 시에도 데이터 충돌이 발생하지 않도록 합니다.
 *
 * 테스트 완료 후 스키마를 삭제하여 리소스를 정리합니다.
 */
class DatabaseTestExtension : SpecExtension {
    override suspend fun intercept(
        spec: Spec,
        execute: suspend (Spec) -> Unit,
    ) {
        PostgreSQLTestContainer.start()

        // Spec별 고유 스키마 생성
        val schemaName = generateSchemaName(spec)
        PostgreSQLTestContainer.createSchema(schemaName)
        PostgreSQLTestContainer.configureSystemProperties(schemaName)

        try {
            execute(spec)
        } finally {
            // 스키마 삭제 (테이블 포함)
            PostgreSQLTestContainer.dropSchema(schemaName)
        }
    }

    /**
     * Spec 클래스명과 타임스탬프를 조합하여 고유한 스키마명을 생성합니다.
     *
     * PostgreSQL 스키마명 규칙:
     * - 소문자와 숫자, 언더스코어만 사용
     * - 63자 제한
     */
    private fun generateSchemaName(spec: Spec): String {
        val className = spec.javaClass.simpleName
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "_")
            .take(30)
        return "test_${className}_${System.nanoTime() % 1_000_000}"
    }
}
