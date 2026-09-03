package io.clroot.hibernate.reactive.repository.query.derived

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class DerivedQueryHqlCompilerTest : DescribeSpec({
    val compiler = DerivedQueryHqlCompiler("User")

    fun compile(name: String): CompiledQuery =
        compiler.compile(DerivedQueryParser.parse(name, CompilerUser::class.java))

    describe("query subjects") {
        it("compiles find, count, exists, and delete queries") {
            compile("findByName").hql shouldBe "FROM User e WHERE e.name = :p0"
            compile("countByName").hql shouldBe "SELECT COUNT(e) FROM User e WHERE e.name = :p0"
            compile("existsByName").hql shouldBe "SELECT 1 FROM User e WHERE e.name = :p0"
            compile("deleteByName").hql shouldBe "FROM User e WHERE e.name = :p0"
        }

        it("compiles distinct find and count queries") {
            compile("findDistinctByName").hql shouldBe "SELECT DISTINCT e FROM User e WHERE e.name = :p0"
            compile("countDistinctByName").hql shouldBe
                "SELECT COUNT(DISTINCT e) FROM User e WHERE e.name = :p0"
        }

        it("compiles a page count query independently of the find subject") {
            val query = DerivedQueryParser.parse("findAllByNameAndAge", CompilerUser::class.java)
            compiler.compileCount(query).hql shouldBe
                "SELECT COUNT(e) FROM User e WHERE (e.name = :p0 AND e.age = :p1)"
        }
    }

    describe("predicates and bindings") {
        it("preserves AND/OR grouping and binding order") {
            val result = compile("findByNameAndAgeOrEmail")

            result.hql shouldBe
                "FROM User e WHERE (e.name = :p0 AND e.age = :p1) OR e.email = :p2"
            result.parameterBindings shouldBe listOf(
                ParameterBinding.DIRECT,
                ParameterBinding.DIRECT,
                ParameterBinding.DIRECT,
            )
        }

        it("compiles comparison, range, null, boolean, and collection operators") {
            compile("findByAgeLessThan").hql shouldContain "e.age < :p0"
            compile("findByAgeLessThanEqual").hql shouldContain "e.age <= :p0"
            compile("findByAgeGreaterThan").hql shouldContain "e.age > :p0"
            compile("findByAgeGreaterThanEqual").hql shouldContain "e.age >= :p0"
            compile("findByAgeBefore").hql shouldContain "e.age < :p0"
            compile("findByAgeAfter").hql shouldContain "e.age > :p0"
            compile("findByAgeBetween").hql shouldContain "e.age BETWEEN :p0 AND :p1"
            compile("findByNameNot").hql shouldContain "e.name <> :p0"
            compile("findByEmailIsNull").hql shouldContain "e.email IS NULL"
            compile("findByEmailIsNotNull").hql shouldContain "e.email IS NOT NULL"
            compile("findByActiveTrue").hql shouldContain "e.active = TRUE"
            compile("findByActiveFalse").hql shouldContain "e.active = FALSE"
            compile("findByTagsIsEmpty").hql shouldContain "e.tags IS EMPTY"
            compile("findByTagsIsNotEmpty").hql shouldContain "e.tags IS NOT EMPTY"
            compile("findByNameIn").parameterBindings shouldBe listOf(ParameterBinding.IN_COLLECTION)
            compile("findByNameNotIn").parameterBindings shouldBe listOf(ParameterBinding.NOT_IN_COLLECTION)
        }

        it("compiles LIKE operators with the existing escaping contract") {
            compile("findByNameLike").hql shouldBe "FROM User e WHERE e.name LIKE :p0"
            compile("findByNameNotLike").hql shouldBe "FROM User e WHERE e.name NOT LIKE :p0"
            compile("findByNameStartingWith").hql shouldBe
                "FROM User e WHERE e.name LIKE :p0 ESCAPE '\\'"
            compile("findByNameEndingWith").parameterBindings shouldBe listOf(ParameterBinding.ENDING_WITH)
            compile("findByNameContaining").parameterBindings shouldBe listOf(ParameterBinding.CONTAINING)
            compile("findByNameNotContaining").hql shouldContain "NOT LIKE :p0 ESCAPE '\\'"

            ParameterBinding.CONTAINING.bind("50%_\\") shouldBe "%50\\%\\_\\\\%"
            ParameterBinding.STARTING_WITH.bind("ab") shouldBe "ab%"
            ParameterBinding.ENDING_WITH.bind("ab") shouldBe "%ab"
        }

        it("requires collection values for IN bindings") {
            ParameterBinding.IN_COLLECTION.bind(listOf("a")) shouldBe listOf("a")
            shouldThrow<IllegalArgumentException> { ParameterBinding.IN_COLLECTION.bind("a") }
            shouldThrow<IllegalArgumentException> { ParameterBinding.NOT_IN_COLLECTION.bind(null) }
        }

        it("rejects operators that have no portable HQL implementation") {
            listOf("findByNameRegex", "findByLocationNear", "findByLocationWithin", "findByNameExists")
                .forEach { methodName ->
                    val error = shouldThrow<UnsupportedOperationException> { compile(methodName) }
                    error.message shouldContain "Derived query type is not supported"
                }
        }
    }

    describe("case handling and ordering") {
        it("applies IgnoreCase to both property and parameter") {
            compile("findByNameIgnoreCase").hql shouldBe
                "FROM User e WHERE LOWER(e.name) = LOWER(:p0)"
            compile("findByNameContainingIgnoreCase").hql shouldBe
                "FROM User e WHERE LOWER(e.name) LIKE LOWER(:p0) ESCAPE '\\'"
        }

        it("rejects explicit IgnoreCase on a non-String property") {
            shouldThrow<IllegalStateException> { compile("findByAgeIgnoreCase") }
        }

        it("applies AllIgnoreCase only where possible") {
            compile("findByNameAndAgeAllIgnoreCase").hql shouldBe
                "FROM User e WHERE (LOWER(e.name) = LOWER(:p0) AND e.age = :p1)"
        }

        it("uses method ordering unless non-empty dynamic ordering replaces it") {
            val query = DerivedQueryParser.parse("findByNameOrderByEmailAsc", CompilerUser::class.java)

            compiler.compile(query).hql shouldContain "ORDER BY e.email ASC"
            compiler.compile(query, emptyList()).hql shouldContain "ORDER BY e.email ASC"
            val dynamic = compiler.compile(query, listOf(QueryOrder("age", SortDirection.DESC)))
            dynamic.hql shouldContain "ORDER BY e.age DESC"
            dynamic.hql shouldNotContain "e.email"
        }

        it("compiles case-insensitive dynamic ordering") {
            val query = DerivedQueryParser.parse("findByName", CompilerUser::class.java)
            compiler.compile(query, listOf(QueryOrder("name", ignoreCase = true))).hql shouldContain
                "ORDER BY LOWER(e.name) ASC"
        }

        it("rejects unsafe dynamic property paths and the class pseudo-property") {
            val query = DerivedQueryParser.parse("findByName", CompilerUser::class.java)
            shouldThrow<IllegalArgumentException> {
                compiler.compile(query, listOf(QueryOrder("name) desc, delete from User")))
            }
            shouldThrow<IllegalArgumentException> {
                compiler.compile(query, listOf(QueryOrder("class")))
            }
        }
    }
})

private data class CompilerUser(
    val name: String,
    val email: String,
    val age: Int,
    val active: Boolean,
    val tags: List<String>,
    val location: CompilerLocation,
)

private data class CompilerLocation(val value: String)
