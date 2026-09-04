package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.repository.query.Query
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Verifies that invalid declarations fail with clear messages rather than
 * silently misbehaving at runtime after startup.
 */
class QueryMethodValidationTest : DescribeSpec({
    val parser = QueryMethodParser(Member::class.java)

    fun parse(methodName: String) = parser.parse(
        MemberRepository::class.java.methods.single { it.name == methodName },
    )

    describe("IgnoreCase") {
        it("compares both sides in lower case") {
            parse("findByNameIgnoreCase").hql shouldBe
                "FROM Member e WHERE LOWER(e.name) = LOWER(:p0)"

            parse("findByName").hql shouldBe "FROM Member e WHERE e.name = :p0"
        }

        it("applies to LIKE conditions as well") {
            parse("findByNameContainingIgnoreCase").hql shouldBe
                "FROM Member e WHERE LOWER(e.name) LIKE LOWER(:p0) ESCAPE '\\'"
        }

        it("rejects IgnoreCase on a non-String property") {
            val error = shouldThrow<IllegalStateException> {
                parse("findByAgeIgnoreCase")
            }

            error.message shouldContain "non-String property"
        }
    }

    describe("Distinct") {
        it("emits SELECT DISTINCT") {
            parse("findDistinctByName").hql shouldBe
                "SELECT DISTINCT e FROM Member e WHERE e.name = :p0"
        }
    }

    describe("Top/First limiting") {
        it("captures the declared limit") {
            parse("findTop3ByOrderByAgeDesc").maxResults shouldBe 3
            parse("findFirstByOrderByAgeDesc").maxResults shouldBe 1
            parse("findByName").maxResults shouldBe null
        }

        it("rejects combining a limit with Pageable") {
            val error = shouldThrow<IllegalStateException> {
                parse("findTop3ByName")
            }

            error.message shouldContain "ambiguous"
        }
    }

    describe("derived query parameters") {
        it("rejects a method whose name needs more arguments than it declares") {
            val error = shouldThrow<IllegalStateException> {
                parse("findByNameAndAge")
            }

            error.message shouldContain "derives 2 query parameter(s)"
        }
    }

    describe("@Query result types") {
        it("accepts scalar projections") {
            parse("sumAges").resultClass shouldBe Long::class.javaObjectType
        }

        it("accepts entity results") {
            parse("findActive").hql shouldContain "FROM Member"
        }

        it("accepts a SELECT preceded by a block comment") {
            parse("findWithComment").hql shouldContain "SELECT e FROM Member"
        }

        it("accepts a SELECT preceded by a CTE") {
            parse("findWithCte").hql shouldContain "WITH recent"
        }
    }
})

private class Member(
    val id: Long,
    val name: String,
    val age: Int,
)

private interface MemberRepository {
    suspend fun findByName(name: String): Member?

    suspend fun findByNameIgnoreCase(name: String): Member?

    suspend fun findByNameContainingIgnoreCase(name: String): List<Member>

    suspend fun findByAgeIgnoreCase(age: Int): Member?

    suspend fun findDistinctByName(name: String): List<Member>

    suspend fun findTop3ByOrderByAgeDesc(): List<Member>

    suspend fun findFirstByOrderByAgeDesc(): Member?

    suspend fun findTop3ByName(name: String, pageable: org.springframework.data.domain.Pageable): List<Member>

    suspend fun findByNameAndAge(name: String): Member?

    @Query("SELECT SUM(e.age) FROM Member e")
    suspend fun sumAges(): Long

    @Query("SELECT e FROM Member e WHERE e.age > 0")
    suspend fun findActive(): List<Member>

    @Query("/* index hint */ SELECT e FROM Member e")
    suspend fun findWithComment(): List<Member>

    @Query(
        "WITH recent AS (SELECT e.id AS id FROM Member e) " +
                "SELECT e FROM Member e WHERE e.id IN (SELECT r.id FROM recent r)",
    )
    suspend fun findWithCte(): List<Member>
}
