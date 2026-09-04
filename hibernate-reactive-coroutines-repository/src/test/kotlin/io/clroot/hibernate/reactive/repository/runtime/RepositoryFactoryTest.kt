package io.clroot.hibernate.reactive.repository.runtime

import io.clroot.hibernate.reactive.ReactiveSessionOperations
import io.clroot.hibernate.reactive.ReadOnlyTransactionException
import io.clroot.hibernate.reactive.repository.query.QueryParameterStyle
import io.clroot.hibernate.reactive.repository.query.derived.ParameterBinding
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.persistence.Id
import jakarta.persistence.metamodel.Metamodel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import org.hibernate.reactive.mutiny.Mutiny

class RepositoryFactoryTest : DescribeSpec({
    describe("framework-neutral repository proxy") {
        it("constructs a proxy without a framework container and preserves identity methods") {
            val session = mockk<Mutiny.Session>()
            val proxy = factory(DelegatingSessionOperations(session)).create(
                NeutralRepository::class.java,
                RuntimeEntity::class.java,
                Long::class.java,
                "RuntimeEntity",
            )

            proxy.toString() shouldBe "RuntimeEntityRepository(proxy)"
            (proxy == proxy) shouldBe true
            proxy.hashCode() shouldBe proxy.hashCode()
        }

        it("dispatches CRUD reads and Flow methods through the session SPI") {
            val session = mockk<Mutiny.Session>()
            val countQuery = mockk<Mutiny.SelectionQuery<Long>>()
            val listQuery = mockk<Mutiny.SelectionQuery<RuntimeEntity>>()
            val entities = listOf(RuntimeEntity(1, "one"), RuntimeEntity(2, "two"))
            every { session.createQuery("SELECT COUNT(e) FROM RuntimeEntity e", Long::class.java) } returns countQuery
            every { countQuery.singleResult } returns Uni.createFrom().item(2L)
            every { session.createQuery("FROM RuntimeEntity", RuntimeEntity::class.java) } returns listQuery
            every { listQuery.resultList } returns Uni.createFrom().item(entities)

            val operations = DelegatingSessionOperations(session)
            val proxy = factory(operations).create(
                NeutralRepository::class.java,
                RuntimeEntity::class.java,
                Long::class.java,
                "RuntimeEntity",
            )

            proxy.count() shouldBe 2L
            proxy.findAll().toList().shouldContainExactly(entities)
            operations.readCalls shouldBe 2
        }

        it("saves an Iterable inside one write boundary") {
            val session = mockk<Mutiny.Session>()
            every { session.persist(any<RuntimeEntity>()) } returns Uni.createFrom().voidItem()
            val operations = DelegatingSessionOperations(session)
            val proxy = factory(operations).create(
                NeutralRepository::class.java,
                RuntimeEntity::class.java,
                Long::class.java,
                "RuntimeEntity",
            )
            val entities = List(2_000) { RuntimeEntity(null, "entity-$it") }

            proxy.saveAll(entities).toList().shouldContainExactly(entities)
            operations.writeCalls shouldBe 1
        }

        it("deletes an Iterable inside one write boundary") {
            val session = mockk<Mutiny.Session>()
            val entities = listOf(RuntimeEntity(1, "one"), RuntimeEntity(2, "two"))
            val managed = listOf(RuntimeEntity(1, "managed-one"), RuntimeEntity(2, "managed-two"))
            entities.zip(managed).forEach { (entity, managedEntity) ->
                every { session.merge(entity) } returns Uni.createFrom().item(managedEntity)
                every { session.remove(managedEntity) } returns Uni.createFrom().voidItem()
            }
            val operations = DelegatingSessionOperations(session)
            val proxy = factory(operations).create(
                NeutralRepository::class.java,
                RuntimeEntity::class.java,
                Long::class.java,
                "RuntimeEntity",
            )

            proxy.deleteAll(entities)

            operations.writeCalls shouldBe 1
            entities.zip(managed).forEach { (entity, managedEntity) ->
                verify(exactly = 1) { session.merge(entity) }
                verify(exactly = 1) { session.remove(managedEntity) }
            }
        }

        it("dispatches prepared derived queries and binds transformed parameters in order") {
            val session = mockk<Mutiny.Session>()
            val query = mockk<Mutiny.SelectionQuery<RuntimeEntity>>(relaxed = true)
            val expected = listOf(RuntimeEntity(1, "alice"))
            val hql = "FROM RuntimeEntity e WHERE e.name LIKE :p0 ESCAPE '\\\\'"
            every { session.createQuery(hql, RuntimeEntity::class.java) } returns query
            every { query.resultList } returns Uni.createFrom().item(expected)

            val prepared = PreparedRepositoryQuery(
                methodName = "findByNameContaining",
                hql = hql,
                countHql = null,
                parameterBindings = listOf(ParameterBinding.CONTAINING),
                returnType = RepositoryQueryReturnType.LIST,
            )
            val proxy = factory(DelegatingSessionOperations(session)).create(
                NeutralRepository::class.java,
                RuntimeEntity::class.java,
                Long::class.java,
                "RuntimeEntity",
                mapOf("findByNameContaining#1" to prepared),
            )

            proxy.findByNameContaining("a_%").shouldContainExactly(expected)
            verify { query.setParameter("p0", "%a\\_\\%%") }
        }

        it("executes annotated descriptors through the neutral parameter contract") {
            val session = mockk<Mutiny.Session>()
            val query = mockk<Mutiny.SelectionQuery<RuntimeEntity>>(relaxed = true)
            val content = listOf(RuntimeEntity(1, "alice"))
            val hql = "FROM RuntimeEntity e WHERE e.name = :name"
            every { session.createQuery(hql, RuntimeEntity::class.java) } returns query
            every { query.resultList } returns Uni.createFrom().item(content)
            val prepared = PreparedRepositoryQuery(
                methodName = "search",
                hql = hql,
                countHql = null,
                parameterBindings = emptyList(),
                returnType = RepositoryQueryReturnType.LIST,
                queryKind = RepositoryQueryKind.ANNOTATED,
                parameterStyle = QueryParameterStyle.NAMED,
                parameterNames = listOf("name"),
                resultClass = RuntimeEntity::class.java,
            )
            val proxy = factory(DelegatingSessionOperations(session)).create(
                AnnotatedRepository::class.java,
                RuntimeEntity::class.java,
                Long::class.java,
                "RuntimeEntity",
                mapOf("search#1" to prepared),
            )

            proxy.search("alice").shouldContainExactly(content)
            verify { query.setParameter("name", "alice") }
        }

        it("runs lifecycle hooks before persisting new entities") {
            val session = mockk<Mutiny.Session>()
            val entity = RuntimeEntity(null, "new")
            every { session.persist(entity) } returns Uni.createFrom().voidItem()
            val events = mutableListOf<Pair<Any, Boolean>>()
            val lifecycle = object : RepositoryEntityLifecycle {
                override suspend fun beforeSave(entity: Any, isNew: Boolean) {
                    events += entity to isNew
                }
            }
            val operations = DelegatingSessionOperations(session)
            val proxy = factory(operations, lifecycle = lifecycle).create(
                NeutralRepository::class.java,
                RuntimeEntity::class.java,
                Long::class.java,
                "RuntimeEntity",
            )

            proxy.save(entity) shouldBe entity
            events shouldContainExactly listOf(entity to true)
            operations.writeCalls shouldBe 1
        }

        it("propagates read-only write rejection from the session SPI") {
            val session = mockk<Mutiny.Session>()
            val operations = object : ReactiveSessionOperations {
                override suspend fun <T> read(block: (Mutiny.Session) -> Uni<T>): T =
                    block(session).awaitSuspending()

                override suspend fun <T> write(block: (Mutiny.Session) -> Uni<T>): T {
                    throw ReadOnlyTransactionException("read only")
                }
            }
            val proxy = factory(operations).create(
                NeutralRepository::class.java,
                RuntimeEntity::class.java,
                Long::class.java,
                "RuntimeEntity",
            )

            shouldThrow<ReadOnlyTransactionException> { proxy.save(RuntimeEntity(null, "new")) }
                .message shouldBe "read only"
        }

        it("supports neutral page requests without an integration adapter") {
            val session = mockk<Mutiny.Session>()
            val query = mockk<Mutiny.SelectionQuery<RuntimeEntity>>(relaxed = true)
            val content = listOf(RuntimeEntity(1, "alice"))
            val hql = "FROM RuntimeEntity e WHERE e.name = :p0"
            every { session.createQuery(hql, RuntimeEntity::class.java) } returns query
            every { query.resultList } returns Uni.createFrom().item(content)
            val prepared = PreparedRepositoryQuery(
                methodName = "findPageByName",
                hql = hql,
                countHql = "SELECT COUNT(e) FROM RuntimeEntity e WHERE e.name = :p0",
                parameterBindings = listOf(ParameterBinding.DIRECT),
                returnType = RepositoryQueryReturnType.PAGE,
            )
            val proxy = factory(DelegatingSessionOperations(session)).create(
                DirectPagedRepository::class.java,
                RuntimeEntity::class.java,
                Long::class.java,
                "RuntimeEntity",
                mapOf("findPageByName#2" to prepared),
            )

            val result = proxy.findPageByName("alice", RepositoryPageRequest(offset = 0, pageSize = 10))

            result.content.shouldContainExactly(content)
            result.totalElements shouldBe 1L
        }

        it("uses adapter hooks for native paging arguments and results") {
            val session = mockk<Mutiny.Session>()
            val query = mockk<Mutiny.SelectionQuery<RuntimeEntity>>(relaxed = true)
            val content = listOf(RuntimeEntity(1, "alice"))
            val hql = "FROM RuntimeEntity e WHERE e.name = :p0"
            every { session.createQuery(hql, RuntimeEntity::class.java) } returns query
            every { query.resultList } returns Uni.createFrom().item(content)
            val adapter = TestPageAdapter
            val prepared = PreparedRepositoryQuery(
                methodName = "findPageByName",
                hql = hql,
                countHql = "SELECT COUNT(e) FROM RuntimeEntity e WHERE e.name = :p0",
                parameterBindings = listOf(ParameterBinding.DIRECT),
                returnType = RepositoryQueryReturnType.PAGE,
            )
            val proxy = factory(DelegatingSessionOperations(session), adapter).create(
                PagedRepository::class.java,
                RuntimeEntity::class.java,
                Long::class.java,
                "RuntimeEntity",
                mapOf("findPageByName#2" to prepared),
            )

            val result = proxy.findPageByName("alice", TestPageRequest(offset = 0, size = 10))
            result shouldBe TestPage(content, 1)
            verify {
                query.setParameter("p0", "alice")
                query.setFirstResult(0)
                query.setMaxResults(10)
            }
        }

        it("reports unknown methods through the shared suggestion helper") {
            val proxy = factory(DelegatingSessionOperations(mockk())).create(
                UnknownRepository::class.java,
                RuntimeEntity::class.java,
                Long::class.java,
                "RuntimeEntity",
            )

            shouldThrow<UnsupportedOperationException> { proxy.sav(RuntimeEntity(null, "x")) }
                .message shouldContain "Did you mean: 'save'"
        }
    }
}) {
    companion object {
        private fun factory(
            sessionOperations: ReactiveSessionOperations,
            adapter: RepositoryRuntimeAdapter = RepositoryRuntimeAdapter.DEFAULT,
            lifecycle: RepositoryEntityLifecycle = RepositoryEntityLifecycle.NONE,
        ) = RepositoryFactory(
            sessionOperations = sessionOperations,
            metamodel = mockk<Metamodel>(relaxed = true),
            runtimeAdapter = adapter,
            entityLifecycle = lifecycle,
        )

        interface NeutralRepository {
            suspend fun save(entity: RuntimeEntity): RuntimeEntity
            suspend fun count(): Long
            fun findAll(): Flow<RuntimeEntity>
            fun saveAll(entities: Iterable<RuntimeEntity>): Flow<RuntimeEntity>
            suspend fun deleteAll(entities: Iterable<RuntimeEntity>)
            suspend fun findByNameContaining(name: String): List<RuntimeEntity>
        }

        interface AnnotatedRepository {
            suspend fun search(name: String): List<RuntimeEntity>
        }

        private interface DirectPagedRepository {
            suspend fun findPageByName(name: String, page: RepositoryPageRequest): RepositoryPage<RuntimeEntity>
        }

        interface PagedRepository {
            suspend fun findPageByName(name: String, page: TestPageRequest): TestPage
        }

        interface UnknownRepository {
            suspend fun sav(entity: RuntimeEntity): RuntimeEntity
        }

        data class RuntimeEntity(@field:Id val id: Long?, val name: String)
        data class TestPageRequest(val offset: Long, val size: Int)
        data class TestPage(val content: List<RuntimeEntity>, val total: Long)

        private object TestPageAdapter : RepositoryRuntimeAdapter {
            override fun adaptArguments(arguments: List<Any?>): RepositoryInvocationArguments {
                val request = arguments.last() as TestPageRequest
                return RepositoryInvocationArguments(
                    queryArguments = arguments.dropLast(1),
                    pageRequest = RepositoryPageRequest(request.offset, request.size, context = request),
                )
            }

            override fun createPage(
                content: List<*>,
                request: RepositoryPageRequest,
                totalElements: Long,
            ): Any = TestPage(content.filterIsInstance<RuntimeEntity>(), totalElements)
        }
    }
}

private class DelegatingSessionOperations(
    private val session: Mutiny.Session,
) : ReactiveSessionOperations {
    var readCalls: Int = 0
    var writeCalls: Int = 0

    override suspend fun <T> read(block: (Mutiny.Session) -> Uni<T>): T {
        readCalls++
        return block(session).awaitSuspending()
    }

    override suspend fun <T> write(block: (Mutiny.Session) -> Uni<T>): T {
        writeCalls++
        return block(session).awaitSuspending()
    }
}
