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
 * Integration test base class with HQL query recording.
 *
 * [HqlRecorder] captures queries for assertions. Its records are cleared before each
 * test so assertions include only queries executed by the current test.
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
            // Delete child tables before parent tables to satisfy foreign key constraints.
            session.createMutationQuery("DELETE FROM ChildEntity").executeUpdate()
            session.createMutationQuery("DELETE FROM ParentEntity").executeUpdate()
            session.createMutationQuery("DELETE FROM VersionedEntity").executeUpdate()
            session.createMutationQuery("DELETE FROM AnotherEntity").executeUpdate()
            session.createMutationQuery("DELETE FROM TestEntity").executeUpdate()
        }.awaitSuspending()
    }
}
