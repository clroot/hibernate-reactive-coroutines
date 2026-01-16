package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.spring.boot.autoconfigure.HibernateReactiveAutoConfiguration
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import io.smallrye.mutiny.coroutines.awaitSuspending
import org.hibernate.reactive.mutiny.Mutiny
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles

/**
 * 통합 테스트 베이스 클래스.
 *
 * PostgreSQL TestContainer와 Spring 컨텍스트를 자동으로 설정합니다.
 * 각 Spec(테스트 클래스)마다 독립된 PostgreSQL 스키마를 사용하여
 * 병렬 테스트 실행 시에도 데이터 충돌이 발생하지 않습니다.
 *
 * - **Spec 간 격리**: 스키마 격리로 병렬 테스트 안전
 * - **테스트 케이스 간 격리**: beforeEach에서 TRUNCATE로 데이터 정리
 *
 * 사용 예:
 * ```kotlin
 * @SpringBootTest
 * class MyIntegrationTest : IntegrationTestBase() {
 *     @Autowired
 *     private lateinit var tx: ReactiveTransactionExecutor
 *
 *     init {
 *         describe("MyFeature") {
 *             it("should work") {
 *                 // test code
 *             }
 *         }
 *     }
 * }
 * ```
 */
@ActiveProfiles("test")
@Import(HibernateReactiveAutoConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class IntegrationTestBase : DescribeSpec() {

    @Autowired
    private lateinit var sessionFactory: Mutiny.SessionFactory

    init {
        extension(DatabaseTestExtension())
        extension(SpringExtension())

        beforeEach {
            clearAllTables()
        }
    }

    /**
     * 모든 테스트 테이블의 데이터를 삭제합니다.
     * 스키마 격리 환경에서 안전하게 동작하도록 DELETE를 사용합니다.
     */
    private suspend fun clearAllTables() {
        sessionFactory.withTransaction { session ->
            // 스키마 격리 환경에서는 DELETE가 더 안전함
            // (currentSchema 설정이 search_path에 영향을 주지 않을 수 있음)
            // FK 제약으로 인해 자식 테이블 먼저 삭제
            session.createMutationQuery("DELETE FROM ChildEntity").executeUpdate()
            session.createMutationQuery("DELETE FROM ParentEntity").executeUpdate()
            session.createMutationQuery("DELETE FROM VersionedEntity").executeUpdate()
            session.createMutationQuery("DELETE FROM AnotherEntity").executeUpdate()
            session.createMutationQuery("DELETE FROM TestEntity").executeUpdate()
        }.awaitSuspending()
    }
}
