package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.repository.query.Modifying
import io.clroot.hibernate.reactive.repository.query.Param
import io.clroot.hibernate.reactive.repository.query.Query
import io.clroot.hibernate.reactive.spring.boot.repository.query.ParameterStyle
import io.clroot.hibernate.reactive.spring.boot.repository.query.QueryReturnType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice

class QueryMethodParserTest : DescribeSpec({
    val parser = QueryMethodParser(User::class.java)

    fun parse(methodName: String) = parser.parse(
        QueryRepository::class.java.methods.single { it.name == methodName },
    )

    describe("@Query Page count query derivation") {
        it("derives a count query for a simple select and removes a multiline order by") {
            parse("findActive").countHql shouldBe
                    "SELECT COUNT(*) FROM User e WHERE e.active = true"
        }

        it("preserves distinct entity semantics") {
            parse("findDistinct").countHql shouldBe
                    "SELECT COUNT(DISTINCT e) FROM User e WHERE e.active = true"
        }

        it("derives a count query from a FROM query") {
            parse("findFrom").countHql shouldBe
                    "SELECT COUNT(*) FROM User e WHERE e.active = true"
        }

        it("ignores structural keywords inside a string literal") {
            parse("findByNote").countHql shouldBe
                    "SELECT COUNT(*) FROM User e " +
                    "WHERE e.note = 'contains ORDER BY, GROUP BY, and JOIN FETCH words'"
        }

        it("ignores structural keywords inside a comment") {
            parse("findWithComment").countHql shouldBe
                    "SELECT COUNT(*) FROM User e /* ORDER BY GROUP BY JOIN FETCH */ " +
                    "WHERE e.active = true"
        }

        it("requires an explicit count query for join fetch") {
            val error = shouldThrow<IllegalStateException> {
                parse("findWithRoles")
            }

            error.message shouldContain "does not support JOIN FETCH"
        }

        it("requires an explicit count query for group by") {
            val error = shouldThrow<IllegalStateException> {
                parse("countByActive")
            }

            error.message shouldContain "does not support GROUP BY"
        }

        it("requires an explicit count query for set operations") {
            val error = shouldThrow<IllegalStateException> {
                parse("findCombined")
            }

            error.message shouldContain "does not support UNION"
        }

        it("does not accept a blank explicit count query for native paging") {
            val error = shouldThrow<IllegalStateException> {
                parse("findNativeWithBlankCount")
            }

            error.message shouldContain "requires explicit countQuery"
        }

        it("uses an explicit count query for join fetch") {
            parse("findWithRolesAndCount").countHql shouldBe
                    "SELECT COUNT(e) FROM User e WHERE e.active = true"
        }

        it("uses an explicit count query for group by") {
            parse("countByActiveWithCount").countHql shouldBe
                    "SELECT COUNT(DISTINCT e.active) FROM User e"
        }

        it("tracks count query parameters separately from order parameters") {
            val prepared = parse("findOrderedWithCount")

            prepared.parameterStyle shouldBe ParameterStyle.NAMED
            prepared.parameterNames shouldBe listOf("active", "priority")
            prepared.countAnnotatedParameters.style shouldBe ParameterStyle.NAMED
            prepared.countAnnotatedParameters.names shouldBe listOf("active")
        }

        it("rejects duplicate method parameter aliases") {
            val error = shouldThrow<IllegalStateException> {
                parse("findWithDuplicateAliases")
            }

            error.message shouldContain "duplicate query parameter name"
        }

        it("rejects a count query with non-contiguous positional labels") {
            val error = shouldThrow<IllegalStateException> {
                parse("findWithNonContiguousCountParameters")
            }

            error.message shouldContain "must start at ?1 and be contiguous"
        }

        it("rejects parameters used only by the count query") {
            val error = shouldThrow<IllegalStateException> {
                parse("findWithCountOnlyParameter")
            }

            error.message shouldContain "not used by the content query"
        }
    }

    describe("@Query projections") {
        it("extracts a scalar aggregate result class") {
            val prepared = parse("countActive")

            prepared.returnType shouldBe QueryReturnType.SINGLE
            prepared.resultClass shouldBe Long::class.javaObjectType
        }

        it("extracts a scalar list element class") {
            val prepared = parse("findNotes")

            prepared.returnType shouldBe QueryReturnType.LIST
            prepared.resultClass shouldBe String::class.java
        }

        it("extracts a constructor DTO list element class") {
            val prepared = parse("findSummaries")

            prepared.returnType shouldBe QueryReturnType.LIST
            prepared.resultClass shouldBe UserSummary::class.java
        }

        it("extracts constructor DTO element classes from Page and Slice") {
            parse("findSummaryPage").resultClass shouldBe UserSummary::class.java
            parse("findSummarySlice").resultClass shouldBe UserSummary::class.java
        }

        it("rejects interface projections during repository initialization") {
            val error = shouldThrow<IllegalStateException> {
                parse("findViews")
            }

            error.message shouldContain "interface projection"
        }

        it("rejects array projections during repository initialization") {
            val error = shouldThrow<IllegalStateException> {
                parse("findRows")
            }

            error.message shouldContain "Tuple/array projection"
        }
    }

    describe("@Modifying return types") {
        it("accepts Unit and preserves its declared return contract") {
            parse("deactivate").returnType shouldBe QueryReturnType.VOID
        }

        it("rejects unsupported return types during repository initialization") {
            val error = shouldThrow<IllegalStateException> {
                parse("deactivateWithMessage")
            }

            error.message shouldContain "must return Int or Unit"
        }

        it("rejects @Modifying without @Query") {
            val error = shouldThrow<IllegalArgumentException> {
                parse("modifyingWithoutQuery")
            }

            error.message shouldContain "requires HRC @Query"
        }
    }
})

private data class User(
    val id: Long,
    val active: Boolean,
    val note: String,
)

private data class UserSummary(
    val note: String,
    val active: Boolean,
)

private interface UserView {
    val note: String
}

private interface QueryRepository {
    @Query("SELECT COUNT(e) FROM User e WHERE e.active = true")
    suspend fun countActive(): Long

    @Query("SELECT e.note FROM User e ORDER BY e.note")
    suspend fun findNotes(): List<String>

    @Query("SELECT new io.clroot.hibernate.reactive.spring.boot.repository.UserSummary(e.note, e.active) FROM User e")
    suspend fun findSummaries(): List<UserSummary>

    @Query(
        value = "SELECT new io.clroot.hibernate.reactive.spring.boot.repository.UserSummary(e.note, e.active) FROM User e",
        countQuery = "SELECT COUNT(e) FROM User e",
    )
    suspend fun findSummaryPage(pageable: Pageable): Page<UserSummary>

    @Query("SELECT new io.clroot.hibernate.reactive.spring.boot.repository.UserSummary(e.note, e.active) FROM User e")
    suspend fun findSummarySlice(pageable: Pageable): Slice<UserSummary>

    @Query("SELECT e.note FROM User e")
    suspend fun findViews(): List<UserView>

    @Query("SELECT e.note, e.active FROM User e")
    suspend fun findRows(): List<Array<Any>>

    @Modifying
    @Query("UPDATE User e SET e.active = false WHERE e.id = :id")
    suspend fun deactivate(id: Long)

    @Modifying
    @Query("UPDATE User e SET e.active = false WHERE e.id = :id")
    suspend fun deactivateWithMessage(id: Long): String

    @Modifying
    suspend fun modifyingWithoutQuery(id: Long): User?

    @Query(
        """
        SELECT e FROM User e WHERE e.active = true
        ORDER BY e.id
        """,
    )
    suspend fun findActive(pageable: Pageable): Page<User>

    @Query("SELECT DISTINCT e FROM User e WHERE e.active = true ORDER BY e.id")
    suspend fun findDistinct(pageable: Pageable): Page<User>

    @Query("FROM User e WHERE e.active = true ORDER BY e.id")
    suspend fun findFrom(pageable: Pageable): Page<User>

    @Query(
        "SELECT e FROM User e " +
                "WHERE e.note = 'contains ORDER BY, GROUP BY, and JOIN FETCH words' ORDER BY e.id",
    )
    suspend fun findByNote(pageable: Pageable): Page<User>

    @Query(
        "SELECT e FROM User e /* ORDER BY GROUP BY JOIN FETCH */ " +
                "WHERE e.active = true ORDER BY e.id",
    )
    suspend fun findWithComment(pageable: Pageable): Page<User>

    @Query("SELECT e FROM User e LEFT JOIN FETCH e.roles WHERE e.active = true")
    suspend fun findWithRoles(pageable: Pageable): Page<User>

    @Query("SELECT e.active FROM User e GROUP BY e.active")
    suspend fun countByActive(pageable: Pageable): Page<Boolean>

    @Query("SELECT e FROM User e UNION SELECT e FROM User e")
    suspend fun findCombined(pageable: Pageable): Page<User>

    @Query(
        value = "SELECT * FROM users",
        nativeQuery = true,
        countQuery = " ",
    )
    suspend fun findNativeWithBlankCount(pageable: Pageable): Page<User>

    @Query(
        value = "SELECT e FROM User e LEFT JOIN FETCH e.roles WHERE e.active = true",
        countQuery = "SELECT COUNT(e) FROM User e WHERE e.active = true",
    )
    suspend fun findWithRolesAndCount(pageable: Pageable): Page<User>

    @Query(
        value = "SELECT e FROM User e GROUP BY e",
        countQuery = "SELECT COUNT(DISTINCT e.active) FROM User e",
    )
    suspend fun countByActiveWithCount(pageable: Pageable): Page<User>

    @Query(
        value = "SELECT e FROM User e WHERE e.active = :active " +
                "ORDER BY CASE WHEN :priority = true THEN 0 ELSE 1 END",
        countQuery = "SELECT COUNT(e) FROM User e WHERE e.active = :active",
    )
    suspend fun findOrderedWithCount(
        active: Boolean,
        priority: Boolean,
        pageable: Pageable,
    ): Page<User>

    @Query("SELECT e FROM User e WHERE e.active = :value")
    suspend fun findWithDuplicateAliases(
        @Param("value") first: Boolean,
        @Param("value") second: Boolean,
    ): List<User>

    @Query(
        value = "SELECT e FROM User e WHERE e.active = ?1 AND e.note = ?2",
        countQuery = "SELECT COUNT(e) FROM User e WHERE e.note = ?2",
    )
    suspend fun findWithNonContiguousCountParameters(
        active: Boolean,
        note: String,
        pageable: Pageable,
    ): Page<User>

    @Query(
        value = "SELECT e FROM User e",
        countQuery = "SELECT COUNT(e) FROM User e WHERE e.active = :active",
    )
    suspend fun findWithCountOnlyParameter(
        active: Boolean,
        pageable: Pageable,
    ): Page<User>
}
