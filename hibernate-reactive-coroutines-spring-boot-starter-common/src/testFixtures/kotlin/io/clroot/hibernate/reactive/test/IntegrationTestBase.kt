package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.spring.boot.autoconfigure.HibernateReactiveAutoConfiguration
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import org.hibernate.reactive.mutiny.Mutiny
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles

/**
 * Base class for integration tests.
 *
 * Each spec uses an isolated PostgreSQL schema for safe parallel execution.
 * Test data is cleared before every test case.
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
     * Clears all test tables with DELETE, which works safely with schema isolation.
     * Child tables are deleted before parent tables to satisfy foreign key constraints.
     */
    private suspend fun clearAllTables() {
        // Delete child tables before parent tables to satisfy foreign key constraints.
        val entityNames = listOf(
            "ChildEntity",
            "ParentEntity",
            "VersionedEntity",
            "AnotherEntity",
            "RenamedAlias",
            "TestEntity",
        )

        sessionFactory.withTransaction { session ->
            // Chain each Uni because unconnected operations are never subscribed.
            entityNames.fold(Uni.createFrom().voidItem()) { chain, entityName ->
                chain.chain { _ ->
                    session.createMutationQuery("DELETE FROM $entityName")
                        .executeUpdate()
                        .replaceWithVoid()
                }
            }
        }.awaitSuspending()
    }
}
