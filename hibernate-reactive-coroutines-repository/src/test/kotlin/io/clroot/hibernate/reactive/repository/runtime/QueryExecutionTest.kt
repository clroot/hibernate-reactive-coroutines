package io.clroot.hibernate.reactive.repository.runtime

import io.clroot.hibernate.reactive.ReactiveSessionOperations
import io.clroot.hibernate.reactive.repository.query.QueryParameterStyle
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import org.hibernate.reactive.mutiny.Mutiny

class QueryExecutionTest : DescribeSpec({
    describe("derived delete semantics") {
        it("removes loaded entities in a write operation and returns the number removed") {
            val fixture = QueryExecutionFixture()
            val entities = listOf(QueryEntity(1), QueryEntity(2))
            val hql = "FROM QueryEntity e WHERE e.active = :p0"
            val query = mockk<Mutiny.SelectionQuery<QueryEntity>>()
            every { fixture.session.createQuery(hql, QueryEntity::class.java) } returns query
            every { query.setParameter("p0", false) } returns query
            every { query.resultList } returns Uni.createFrom().item(entities)
            every { fixture.session.removeAll(*entities.toTypedArray()) } returns Uni.createFrom().voidItem()

            fixture.operations.executeDeleteQuery(hql, listOf(false)) shouldBe 2L

            fixture.writeCalls shouldBe 1
            fixture.readCalls shouldBe 0
            verifyOrder {
                query.setParameter("p0", false)
                query.resultList
                fixture.session.removeAll(*entities.toTypedArray())
            }
        }

        it("returns zero without removing anything when no entities match") {
            val fixture = QueryExecutionFixture()
            val query = mockk<Mutiny.SelectionQuery<QueryEntity>>()
            every { fixture.session.createQuery("FROM QueryEntity", QueryEntity::class.java) } returns query
            every { query.resultList } returns Uni.createFrom().item(emptyList())

            fixture.operations.executeDeleteQuery("FROM QueryEntity", emptyList()) shouldBe 0L

            fixture.writeCalls shouldBe 1
            verify(exactly = 0) { fixture.session.removeAll(*anyVararg()) }
        }

        it("propagates a removal failure instead of reporting a successful delete count") {
            val fixture = QueryExecutionFixture()
            val entity = QueryEntity(1)
            val failure = IllegalStateException("optimistic locking failed")
            val query = mockk<Mutiny.SelectionQuery<QueryEntity>>()
            every { fixture.session.createQuery("FROM QueryEntity", QueryEntity::class.java) } returns query
            every { query.resultList } returns Uni.createFrom().item(listOf(entity))
            every { fixture.session.removeAll(entity) } returns Uni.createFrom().failure(failure)

            val thrown = shouldThrow<IllegalStateException> {
                fixture.operations.executeDeleteQuery("FROM QueryEntity", emptyList())
            }
            thrown.message shouldBe failure.message
            fixture.writeCalls shouldBe 1
        }
    }

    describe("modifying annotated queries") {
        for (clear in listOf(false, true)) {
            for (affected in listOf(0, 2)) {
                it("returns $affected affected rows and honors clearAutomatically=$clear") {
                    val fixture = QueryExecutionFixture()
                    val prepared = preparedQuery(
                        "UPDATE QueryEntity e SET e.active = :active WHERE e.id = :id",
                        QueryParameterStyle.NAMED,
                    ).copy(clearAutomatically = clear, parameterNames = listOf("id", "active", "unused"))
                    val query = mockk<Mutiny.MutationQuery>()
                    every { fixture.session.createMutationQuery(prepared.hql) } returns query
                    every { query.setParameter("id", 7L) } returns query
                    every { query.setParameter("active", false) } returns query
                    every { query.executeUpdate() } returns Uni.createFrom().item(affected)
                    every { fixture.session.clear() } returns fixture.session

                    fixture.operations.executeModifyingAnnotatedQuery(prepared, listOf(7L, false, "unused")) shouldBe affected

                    fixture.writeCalls shouldBe 1
                    fixture.readCalls shouldBe 0
                    verify(exactly = 1) {
                        query.setParameter("id", 7L)
                        query.setParameter("active", false)
                    }
                    verify(exactly = 0) { query.setParameter("unused", any<String>()) }
                    verify(exactly = if (clear) 1 else 0) { fixture.session.clear() }
                    if (clear) {
                        verifyOrder {
                            query.executeUpdate()
                            fixture.session.clear()
                        }
                    }
                }
            }
        }

        it("binds positional parameters by their index even when they appear out of order") {
            val fixture = QueryExecutionFixture()
            val prepared = preparedQuery(
                "UPDATE QueryEntity e SET e.active = ?2 WHERE e.id = ?1",
                QueryParameterStyle.POSITIONAL,
            )
            val query = mockk<Mutiny.MutationQuery>()
            every { fixture.session.createMutationQuery(prepared.hql) } returns query
            every { query.setParameter(1, 7L) } returns query
            every { query.setParameter(2, false) } returns query
            every { query.executeUpdate() } returns Uni.createFrom().item(1)

            fixture.operations.executeModifyingAnnotatedQuery(prepared, listOf(7L, false)) shouldBe 1

            verify(exactly = 1) {
                query.setParameter(1, 7L)
                query.setParameter(2, false)
            }
        }

        it("does not clear the session after a failed update and preserves the original failure") {
            val fixture = QueryExecutionFixture()
            val prepared = preparedQuery("DELETE FROM QueryEntity").copy(clearAutomatically = true)
            val failure = IllegalStateException("constraint violation")
            val query = mockk<Mutiny.MutationQuery>()
            every { fixture.session.createMutationQuery(prepared.hql) } returns query
            every { query.executeUpdate() } returns Uni.createFrom().failure(failure)

            val thrown = shouldThrow<IllegalStateException> {
                fixture.operations.executeModifyingAnnotatedQuery(prepared, emptyList())
            }
            thrown.message shouldBe failure.message

            fixture.writeCalls shouldBe 1
            verify(exactly = 0) { fixture.session.clear() }
        }

        it("rejects native modifications before opening a write operation") {
            val fixture = QueryExecutionFixture()
            val prepared = preparedQuery("DELETE FROM query_entity").copy(isNativeQuery = true)

            shouldThrow<UnsupportedOperationException> {
                fixture.operations.executeModifyingAnnotatedQuery(prepared, emptyList())
            }

            fixture.writeCalls shouldBe 0
            fixture.readCalls shouldBe 0
            confirmVerified(fixture.session)
        }
    }

    describe("annotated projection execution") {
        for (native in listOf(false, true)) {
            it("uses the projection type for native=$native single and list queries") {
                val fixture = QueryExecutionFixture()
                val hql = if (native) "SELECT name FROM query_entity WHERE id = ?1"
                    else "SELECT e.name FROM QueryEntity e WHERE e.id = ?1"
                val prepared = preparedQuery(hql, QueryParameterStyle.POSITIONAL)
                    .copy(isNativeQuery = native, resultClass = String::class.java)
                val query = mockk<Mutiny.Query<String>>()
                if (native) {
                    every { fixture.session.createNativeQuery(prepared.hql, String::class.java) } returns query
                } else {
                    every { fixture.session.createQuery(prepared.hql, String::class.java) } returns query
                }
                every { query.setParameter(1, 7L) } returns query
                every { query.singleResultOrNull } returns Uni.createFrom().item("alice")
                every { query.resultList } returns Uni.createFrom().item(listOf("alice", "bob"))

                fixture.operations.executeSingleAnnotatedQuery(prepared, listOf(7L)) shouldBe "alice"
                fixture.operations.executeListAnnotatedQuery(prepared, listOf(7L)) shouldBe listOf("alice", "bob")

                fixture.readCalls shouldBe 2
                fixture.writeCalls shouldBe 0
                verify(exactly = 2) { query.setParameter(1, 7L) }
            }
        }

        it("returns null for a missing single result") {
            val fixture = QueryExecutionFixture()
            val prepared = preparedQuery("FROM QueryEntity").copy(resultClass = QueryEntity::class.java)
            val query = mockk<Mutiny.SelectionQuery<QueryEntity>>()
            every { fixture.session.createQuery(prepared.hql, QueryEntity::class.java) } returns query
            every { query.singleResultOrNull } returns Uni.createFrom().nullItem()

            fixture.operations.executeSingleAnnotatedQuery(prepared, emptyList()) shouldBe null
            fixture.readCalls shouldBe 1
        }

        it("retains the entity query type for an annotated method declaring a supertype") {
            val fixture = QueryExecutionFixture()
            val prepared = preparedQuery("FROM QueryEntity").copy(resultClass = Any::class.java)
            val entity = QueryEntity(1)
            val query = mockk<Mutiny.SelectionQuery<QueryEntity>>()
            every { fixture.session.createQuery(prepared.hql, QueryEntity::class.java) } returns query
            every { query.singleResultOrNull } returns Uni.createFrom().item(entity)

            fixture.operations.executeSingleAnnotatedQuery(prepared, emptyList()) shouldBeSameInstanceAs entity
        }

        it("maps a null count to zero") {
            val fixture = QueryExecutionFixture()
            val hql = "SELECT COUNT(e) FROM QueryEntity e"
            val query = mockk<Mutiny.SelectionQuery<Long>>()
            every { fixture.session.createQuery(hql, Long::class.javaObjectType) } returns query
            every { query.singleResult } returns Uni.createFrom().nullItem()

            fixture.operations.executeCountQuery(hql, emptyList()) shouldBe 0L
            fixture.readCalls shouldBe 1
            fixture.writeCalls shouldBe 0
        }
    }
})

private data class QueryEntity(val id: Long)

private fun preparedQuery(
    hql: String,
    style: QueryParameterStyle = QueryParameterStyle.NONE,
): PreparedRepositoryQuery = PreparedRepositoryQuery(
    methodName = "query",
    hql = hql,
    countHql = null,
    parameterBindings = emptyList(),
    returnType = RepositoryQueryReturnType.MODIFYING,
    queryKind = RepositoryQueryKind.ANNOTATED,
    parameterStyle = style,
)

private class QueryExecutionFixture : ReactiveSessionOperations {
    val session = mockk<Mutiny.Session>()
    val operations = QueryOperations(QueryEntity::class.java, this, mockk())
    var readCalls = 0
        private set
    var writeCalls = 0
        private set

    override suspend fun <T> read(block: (Mutiny.Session) -> Uni<T>): T {
        readCalls++
        return block(session).awaitSuspending()
    }

    override suspend fun <T> write(block: (Mutiny.Session) -> Uni<T>): T {
        writeCalls++
        return block(session).awaitSuspending()
    }
}
