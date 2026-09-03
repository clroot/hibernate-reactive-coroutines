package io.clroot.hibernate.reactive.repository

import io.clroot.hibernate.reactive.repository.query.Modifying
import io.clroot.hibernate.reactive.repository.query.Param
import io.clroot.hibernate.reactive.repository.query.Query
import io.clroot.hibernate.reactive.repository.query.QueryParameterStyle
import io.clroot.hibernate.reactive.repository.query.derived.ParameterBinding
import io.clroot.hibernate.reactive.repository.runtime.RepositoryQueryKind
import io.clroot.hibernate.reactive.repository.runtime.RepositoryQueryReturnType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKeys
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.data.Order
import jakarta.data.Sort
import jakarta.data.page.Page
import jakarta.data.page.PageRequest

class JakartaDataQueryMethodParserTest : DescribeSpec({
    val parser = JakartaDataQueryMethodParser(User::class.java)

    fun parse(methodName: String) = parser.parse(
        JakartaRepository::class.java.methods.single { it.name == methodName },
    )

    fun parseInvalid(methodName: String) = parser.parse(
        InvalidRepository::class.java.methods.single { it.name == methodName },
    )

    describe("HRC query metadata with Jakarta Data paging") {
        it("normalizes abbreviated HQL and translates named parameters and Page metadata") {
            val prepared = parse("findActive")

            prepared.hql shouldBe "FROM User e where active = :enabled"
            prepared.countHql shouldBe "SELECT COUNT(*) FROM User e where active = :enabled"
            prepared.parameterStyle shouldBe QueryParameterStyle.NAMED
            prepared.parameterNames.shouldContainExactly("enabled")
            prepared.returnType shouldBe RepositoryQueryReturnType.PAGE
            prepared.queryKind shouldBe RepositoryQueryKind.ANNOTATED
            prepared.resultClass shouldBe User::class.java
        }

        it("honors modifying queries and preserves supported row-count contracts") {
            val update = parse("deactivate")
            val delete = parse("deleteInactive")
            val reset = parse("resetNames")

            update.isModifying shouldBe true
            update.returnType shouldBe RepositoryQueryReturnType.MODIFYING
            delete.isModifying shouldBe true
            delete.returnType shouldBe RepositoryQueryReturnType.LONG
            reset.isModifying shouldBe true
            reset.returnType shouldBe RepositoryQueryReturnType.VOID
        }

        it("rejects unsupported modifying return types") {
            shouldThrow<IllegalStateException> { parseInvalid("invalidUpdate") }
                .message shouldContain "must return Int, Long, or Unit"
        }

        it("allows PageRequest on a list query and excludes it from query parameters") {
            val prepared = parse("searchNames")

            prepared.returnType shouldBe RepositoryQueryReturnType.LIST
            prepared.parameterNames.shouldContainExactly("prefix")
            prepared.resultClass shouldBe String::class.java
        }

        it("uses common HRC query and modifying options") {
            val prepared = parse("findWithRoles")

            prepared.countHql shouldBe "SELECT COUNT(e) FROM User e WHERE e.active = :active"
            prepared.isNativeQuery shouldBe false
            parse("clearInactive").clearAutomatically shouldBe true
        }

        it("supports native Page queries when an explicit count query is supplied") {
            val prepared = parse("findNative")

            prepared.isNativeQuery shouldBe true
            prepared.countHql shouldBe "SELECT COUNT(*) FROM users WHERE active = :active"
        }

        it("requires an explicit count query for native Page queries") {
            shouldThrow<IllegalStateException> { parseInvalid("nativeWithoutCount") }
                .message shouldContain "requires an explicit countQuery"
        }

        it("rejects query options where the shared runtime cannot apply them") {
            shouldThrow<IllegalStateException> { parseInvalid("nativeUpdate") }
                .message shouldContain "Native update/delete"
            shouldThrow<IllegalStateException> { parseInvalid("clearSelect") }
                .message shouldContain "cannot have a SELECT query"
            shouldThrow<IllegalStateException> { parseInvalid("countOnList") }
                .message shouldContain "requires a Page return type"
        }

        it("requires @Modifying for update and delete query statements") {
            shouldThrow<IllegalStateException> { parseInvalid("missingModifying") }
                .message shouldContain "missing @Modifying"
        }
    }

    describe("HRC method-name query extension") {
        it("compiles derived queries with Jakarta Data Sort as a special parameter") {
            val prepared = parse("findByNameContaining")

            prepared.hql shouldContain "e.name LIKE :p0"
            prepared.parameterBindings.shouldContainExactly(ParameterBinding.CONTAINING)
            prepared.returnType shouldBe RepositoryQueryReturnType.LIST
        }

        it("derives a count query for Jakarta Data Page results") {
            val prepared = parse("findByActive")

            prepared.returnType shouldBe RepositoryQueryReturnType.PAGE
            prepared.countHql shouldContain "SELECT COUNT(e)"
        }

        it("requires Page-returning methods to accept PageRequest") {
            shouldThrow<IllegalStateException> { parseInvalid("findByName") }
                .message shouldContain "has no PageRequest"
        }

        it("prepares all custom suspend methods and excludes base CRUD methods") {
            val prepared = parser.parseRepository(JakartaRepository::class.java)

            prepared.shouldContainKeys(
                "findActive#3",
                "deactivate#1",
                "deleteInactive#0",
                "resetNames#0",
                "searchNames#2",
                "findByNameContaining#2",
                "findByActive#2",
            )
            prepared.keys.none { it.startsWith("save#") } shouldBe true
        }
    }
})

private data class User(
    val id: Long,
    val name: String,
    val active: Boolean,
)

private interface JakartaRepository : CoroutineCrudRepository<User, Long> {
    @Query("where active = :enabled")
    suspend fun findActive(
        @Param("enabled") active: Boolean,
        pageRequest: PageRequest,
        order: Order<User>,
    ): Page<User>

    @Modifying
    @Query("UPDATE User e SET e.active = false WHERE e.id = :id")
    suspend fun deactivate(id: Long): Int

    @Modifying
    @Query("DELETE FROM User e WHERE e.active = false")
    suspend fun deleteInactive(): Long

    @Modifying
    @Query("UPDATE User e SET e.name = ''")
    suspend fun resetNames()

    @Query("SELECT e.name FROM User e WHERE e.name LIKE :prefix")
    suspend fun searchNames(prefix: String, pageRequest: PageRequest): List<String>

    suspend fun findByNameContaining(name: String, sort: Sort<User>): List<User>

    suspend fun findByActive(active: Boolean, pageRequest: PageRequest): Page<User>

    @Query(
        value = "SELECT e FROM User e LEFT JOIN FETCH e.roles WHERE e.active = :active",
        countQuery = "SELECT COUNT(e) FROM User e WHERE e.active = :active",
    )
    suspend fun findWithRoles(active: Boolean, pageRequest: PageRequest): Page<User>

    @Modifying(clearAutomatically = true)
    @Query("UPDATE User e SET e.active = false WHERE e.active = true")
    suspend fun clearInactive()

    @Query(
        value = "SELECT * FROM users WHERE active = :active",
        nativeQuery = true,
        countQuery = "SELECT COUNT(*) FROM users WHERE active = :active",
    )
    suspend fun findNative(active: Boolean, pageRequest: PageRequest): Page<User>
}

private interface InvalidRepository {
    @Modifying
    @Query("UPDATE User e SET e.name = ''")
    suspend fun invalidUpdate(): String

    @Query("UPDATE User e SET e.name = ''")
    suspend fun missingModifying(): Int

    suspend fun findByName(name: String): Page<User>

    @Query(value = "SELECT * FROM users", nativeQuery = true)
    suspend fun nativeWithoutCount(pageRequest: PageRequest): Page<User>

    @Modifying
    @Query(value = "UPDATE users SET active = false", nativeQuery = true)
    suspend fun nativeUpdate(): Int

    @Modifying(clearAutomatically = true)
    @Query("SELECT e FROM User e")
    suspend fun clearSelect(): List<User>

    @Query(
        value = "SELECT e FROM User e",
        countQuery = "SELECT COUNT(e) FROM User e",
    )
    suspend fun countOnList(): List<User>
}
