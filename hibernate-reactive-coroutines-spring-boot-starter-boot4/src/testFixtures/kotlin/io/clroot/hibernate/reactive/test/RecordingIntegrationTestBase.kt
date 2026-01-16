package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.spring.boot.autoconfigure.HibernateReactiveAutoConfiguration
import io.clroot.hibernate.reactive.test.recorder.HqlRecorder
import io.clroot.hibernate.reactive.test.recorder.RecordingTestConfiguration
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import io.smallrye.mutiny.coroutines.awaitSuspending
import org.hibernate.reactive.mutiny.Mutiny
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles

/**
 * HQL 쿼리 기록 기능이 포함된 통합 테스트 베이스 클래스.
 *
 * [IntegrationTestBase]의 모든 기능에 더해 [HqlRecorder]를 통한
 * 쿼리 캡처 및 검증 기능을 제공합니다.
 *
 * 각 테스트 전에 쿼리 기록이 자동으로 초기화됩니다.
 *
 * 사용 예:
 * ```kotlin
 * @SpringBootTest
 * class QueryVerificationTest : RecordingIntegrationTestBase() {
 *     @Autowired
 *     private lateinit var repository: TestEntityRepository
 *
 *     init {
 *         describe("쿼리 검증") {
 *             it("findByName은 올바른 HQL을 생성한다") {
 *                 repository.findByName("test")
 *
 *                 hqlRecorder.assertQueryCount(1)
 *                 hqlRecorder.assertLastQueryContains("WHERE e.name = :p0")
 *             }
 *         }
 *     }
 * }
 * ```
 */
@ActiveProfiles("test")
@Import(HibernateReactiveAutoConfiguration::class, RecordingTestConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class RecordingIntegrationTestBase : DescribeSpec() {

    @Autowired
    protected lateinit var hqlRecorder: HqlRecorder

    @Autowired
    private lateinit var sessionFactory: Mutiny.SessionFactory

    init {
        extension(DatabaseTestExtension())
        extension(SpringExtension())

        beforeEach {
            clearAllTables()
            hqlRecorder.clear()
        }
    }

    private suspend fun clearAllTables() {
        sessionFactory.withTransaction { session ->
            // FK 제약조건 순서: 자식 → 부모
            session.createMutationQuery("DELETE FROM ChildEntity").executeUpdate()
            session.createMutationQuery("DELETE FROM ParentEntity").executeUpdate()
            session.createMutationQuery("DELETE FROM VersionedEntity").executeUpdate()
            session.createMutationQuery("DELETE FROM AnotherEntity").executeUpdate()
            session.createMutationQuery("DELETE FROM TestEntity").executeUpdate()
        }.awaitSuspending()
    }
}
