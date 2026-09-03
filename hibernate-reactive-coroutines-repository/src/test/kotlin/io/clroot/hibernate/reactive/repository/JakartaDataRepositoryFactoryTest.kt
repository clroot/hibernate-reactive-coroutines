package io.clroot.hibernate.reactive.repository

import io.clroot.hibernate.reactive.ReactiveSessionOperations
import io.clroot.hibernate.reactive.repository.query.Modifying
import io.clroot.hibernate.reactive.repository.query.Param
import io.clroot.hibernate.reactive.repository.query.Query
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.data.Order
import jakarta.data.Sort
import jakarta.data.page.PageRequest
import jakarta.data.repository.DataRepository
import jakarta.persistence.Id
import jakarta.persistence.metamodel.Attribute
import jakarta.persistence.metamodel.ManagedType
import jakarta.persistence.metamodel.Metamodel
import jakarta.persistence.metamodel.SingularAttribute
import org.hibernate.reactive.mutiny.Mutiny

class JakartaDataRepositoryFactoryTest : DescribeSpec({
    describe("Jakarta Data coroutine repository factory") {
        it("creates a DataRepository-compatible proxy and executes annotated select queries") {
            val session = mockk<Mutiny.Session>()
            val query = mockk<Mutiny.SelectionQuery<FactoryEntity>>(relaxed = true)
            val expected = listOf(FactoryEntity(1, "alice", true))
            val hql = "FROM FactoryEntity e WHERE e.name = :name"
            every { session.createQuery(hql, FactoryEntity::class.java) } returns query
            every { query.resultList } returns Uni.createFrom().item(expected)

            val repository = factory(session).create(
                FactoryRepository::class.java,
                FactoryEntity::class.java,
                Long::class.java,
            )

            DataRepository::class.java.isInstance(repository) shouldBe true
            repository.findNamed("alice").shouldContainExactly(expected)
            verify { query.setParameter("name", "alice") }
        }

        it("executes modifying queries with a Long row count") {
            val session = mockk<Mutiny.Session>()
            val mutation = mockk<Mutiny.MutationQuery>(relaxed = true)
            val hql = "UPDATE FactoryEntity e SET e.active = false WHERE e.id = :id"
            every { session.createMutationQuery(hql) } returns mutation
            every { mutation.executeUpdate() } returns Uni.createFrom().item(3)

            val repository = factory(session).create(
                FactoryRepository::class.java,
                FactoryEntity::class.java,
                Long::class.java,
            )

            repository.deactivate(10) shouldBe 3L
            verify { mutation.setParameter("id", 10L) }
        }

        it("applies Jakarta Data Order to the built-in findAll method") {
            val session = mockk<Mutiny.Session>()
            val query = mockk<Mutiny.SelectionQuery<FactoryEntity>>(relaxed = true)
            val expected = listOf(FactoryEntity(1, "alice", true))
            val metamodel = mockk<Metamodel>()
            val entityType = mockk<ManagedType<FactoryEntity>>()
            val nameAttribute = mockk<SingularAttribute<FactoryEntity, String>>()
            every { metamodel.managedType(FactoryEntity::class.java) } returns entityType
            every { entityType.getAttribute("name") } returns nameAttribute
            every { nameAttribute.persistentAttributeType } returns Attribute.PersistentAttributeType.BASIC
            every { nameAttribute.javaType } returns String::class.java
            every {
                session.createQuery(
                    "FROM FactoryEntity e ORDER BY LOWER(e.name) ASC",
                    FactoryEntity::class.java,
                )
            } returns query
            every { query.resultList } returns Uni.createFrom().item(expected)

            val repository = factory(session, metamodel).create(
                FactoryRepository::class.java,
                FactoryEntity::class.java,
                Long::class.java,
            )

            repository.findAll(Order.by(Sort.ascIgnoreCase("name"))).shouldContainExactly(expected)
        }

        it("over-fetches total-free base pages to determine an exact last page without a count query") {
            val session = mockk<Mutiny.Session>()
            val query = mockk<Mutiny.SelectionQuery<FactoryEntity>>(relaxed = true)
            val expected = listOf(
                FactoryEntity(1, "alice", true),
                FactoryEntity(2, "bob", true),
            )
            every { session.createQuery("FROM FactoryEntity e", FactoryEntity::class.java) } returns query
            every { query.resultList } returns Uni.createFrom().item(expected)

            val repository = factory(session).create(
                FactoryRepository::class.java,
                FactoryEntity::class.java,
                Long::class.java,
            )

            val page = repository.findAll(PageRequest.ofPage(1, 2, false))

            page.content().shouldContainExactly(expected)
            page.hasTotals() shouldBe false
            page.hasNext() shouldBe false
            verify { query.setMaxResults(3) }
            verify(exactly = 0) {
                session.createQuery("SELECT COUNT(e) FROM FactoryEntity e", Long::class.java)
            }
        }

        it("applies PageRequest offset and size to list-returning HRC queries") {
            val session = mockk<Mutiny.Session>()
            val query = mockk<Mutiny.SelectionQuery<FactoryEntity>>(relaxed = true)
            val expected = listOf(FactoryEntity(3, "carol", true))
            val hql = "FROM FactoryEntity e ORDER BY e.id"
            every { session.createQuery(hql, FactoryEntity::class.java) } returns query
            every { query.resultList } returns Uni.createFrom().item(expected)

            val repository = factory(session).create(
                FactoryRepository::class.java,
                FactoryEntity::class.java,
                Long::class.java,
            )

            repository.findWindow(PageRequest.ofPage(2, 2, false)).shouldContainExactly(expected)
            verify {
                query.setFirstResult(2)
                query.setMaxResults(2)
            }
        }

        it("executes native HRC queries through the shared runtime") {
            val session = mockk<Mutiny.Session>()
            val query = mockk<Mutiny.SelectionQuery<FactoryEntity>>(relaxed = true)
            val expected = listOf(FactoryEntity(1, "alice", true))
            val sql = "SELECT * FROM factory_entity WHERE active = :active"
            every { session.createNativeQuery(sql, FactoryEntity::class.java) } returns query
            every { query.resultList } returns Uni.createFrom().item(expected)

            val repository = factory(session).create(
                FactoryRepository::class.java,
                FactoryEntity::class.java,
                Long::class.java,
            )

            repository.findNative(true).shouldContainExactly(expected)
            verify { query.setParameter("active", true) }
        }
    }
}) {
    companion object {
        private fun factory(
            session: Mutiny.Session,
            metamodel: Metamodel = mockk(relaxed = true),
        ) = JakartaDataRepositoryFactory(
            sessionOperations = TestSessionOperations(session),
            metamodel = metamodel,
        )
    }

    data class FactoryEntity(
        @field:Id val id: Long?,
        val name: String,
        val active: Boolean,
    )

    interface FactoryRepository : CoroutineCrudRepository<FactoryEntity, Long> {
        @Query("FROM FactoryEntity e WHERE e.name = :name")
        suspend fun findNamed(@Param("name") value: String): List<FactoryEntity>

        @Modifying
        @Query("UPDATE FactoryEntity e SET e.active = false WHERE e.id = :id")
        suspend fun deactivate(@Param("id") id: Long): Long

        @Query("FROM FactoryEntity e ORDER BY e.id")
        suspend fun findWindow(pageRequest: PageRequest): List<FactoryEntity>

        @Query(value = "SELECT * FROM factory_entity WHERE active = :active", nativeQuery = true)
        suspend fun findNative(active: Boolean): List<FactoryEntity>
    }
}

private class TestSessionOperations(
    private val session: Mutiny.Session,
) : ReactiveSessionOperations {
    override suspend fun <T> read(block: (Mutiny.Session) -> Uni<T>): T = block(session).awaitSuspending()

    override suspend fun <T> write(block: (Mutiny.Session) -> Uni<T>): T = block(session).awaitSuspending()
}
