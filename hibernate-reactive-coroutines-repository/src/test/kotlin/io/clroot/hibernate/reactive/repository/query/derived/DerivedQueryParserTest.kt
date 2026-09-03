package io.clroot.hibernate.reactive.repository.query.derived

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class DerivedQueryParserTest : DescribeSpec({
    fun parse(name: String): DerivedQuery = DerivedQueryParser.parse(name, ParserUser::class.java)

    describe("subjects and modifiers") {
        listOf("find", "read", "get", "query", "search", "stream").forEach { prefix ->
            it("parses $prefix as a find query") {
                parse("${prefix}ByName").subject shouldBe QuerySubject.FIND
            }
        }

        it("parses count, exists, delete, and remove") {
            parse("countByName").subject shouldBe QuerySubject.COUNT
            parse("existsByName").subject shouldBe QuerySubject.EXISTS
            parse("deleteByName").subject shouldBe QuerySubject.DELETE
            parse("removeByName").subject shouldBe QuerySubject.DELETE
        }

        it("parses distinct and limits") {
            parse("findDistinctByName").distinct shouldBe true
            parse("findTop3ByName").limit shouldBe 3
            parse("findFirstByName").limit shouldBe 1
            parse("findByName").limit shouldBe null
        }

        it("ignores Kotlin JVM name mangling suffixes") {
            parse("findByName-generatedSuffix") shouldBe parse("findByName")
        }
    }

    describe("predicate grammar") {
        it("preserves OR-of-AND grouping and parameter order") {
            val query = parse("findByNameAndAgeOrEmail")

            query.predicate.disjuncts shouldHaveSize 2
            query.predicate.disjuncts[0].predicates.map { it.property.value } shouldBe listOf("name", "age")
            query.predicate.disjuncts[1].predicates.map { it.property.value } shouldBe listOf("email")
        }

        it("resolves nested paths with longest-property-first semantics") {
            parse("findByAddressCity").predicate.single().property.value shouldBe "address.city"
            parse("findByUserAddressCity").predicate.single().property.value shouldBe "userAddress.city"
            parse("findByAddress_City").predicate.single().property.value shouldBe "address.city"
        }

        it("rejects unknown properties") {
            shouldThrow<IllegalArgumentException> {
                parse("findByMissing")
            }.message shouldContain "No property 'missing'"
        }

        it("recognizes all supported operator aliases") {
            val cases = mapOf(
                "findByAgeIsBetween" to PredicateOperator.BETWEEN,
                "findByEmailNotNull" to PredicateOperator.IS_NOT_NULL,
                "findByEmailNull" to PredicateOperator.IS_NULL,
                "findByAgeLessThan" to PredicateOperator.LESS_THAN,
                "findByAgeLessThanEqual" to PredicateOperator.LESS_THAN_EQUAL,
                "findByAgeGreaterThan" to PredicateOperator.GREATER_THAN,
                "findByAgeGreaterThanEqual" to PredicateOperator.GREATER_THAN_EQUAL,
                "findByAgeBefore" to PredicateOperator.BEFORE,
                "findByAgeAfter" to PredicateOperator.AFTER,
                "findByNameNotLike" to PredicateOperator.NOT_LIKE,
                "findByNameLike" to PredicateOperator.LIKE,
                "findByNameStartsWith" to PredicateOperator.STARTING_WITH,
                "findByNameEndsWith" to PredicateOperator.ENDING_WITH,
                "findByTagsNotEmpty" to PredicateOperator.IS_NOT_EMPTY,
                "findByTagsEmpty" to PredicateOperator.IS_EMPTY,
                "findByNameNotContains" to PredicateOperator.NOT_CONTAINING,
                "findByNameContains" to PredicateOperator.CONTAINING,
                "findByNameNotIn" to PredicateOperator.NOT_IN,
                "findByNameIn" to PredicateOperator.IN,
                "findByNameNot" to PredicateOperator.NOT_EQUALS,
                "findByNameEquals" to PredicateOperator.EQUALS,
                "findByActiveTrue" to PredicateOperator.TRUE,
                "findByActiveFalse" to PredicateOperator.FALSE,
            )

            cases.forEach { (methodName, operator) ->
                parse(methodName).predicate.single().operator shouldBe operator
            }
        }

        it("recognizes unsupported operators so the compiler can reject them") {
            parse("findByNameRegex").predicate.single().operator shouldBe PredicateOperator.REGEX
            parse("findByLocationNear").predicate.single().operator shouldBe PredicateOperator.NEAR
            parse("findByLocationWithin").predicate.single().operator shouldBe PredicateOperator.WITHIN
            parse("findByNameExists").predicate.single().operator shouldBe PredicateOperator.EXISTS
        }
    }

    describe("case handling and ordering") {
        it("distinguishes per-property and all-property ignore case") {
            parse("findByNameIgnoreCase").predicate.single().ignoreCase shouldBe IgnoreCaseMode.ALWAYS

            val all = parse("findByNameAndAgeAllIgnoreCase")
            all.predicate.disjuncts.single().predicates.map { it.ignoreCase } shouldBe
                listOf(IgnoreCaseMode.WHEN_POSSIBLE, IgnoreCaseMode.WHEN_POSSIBLE)
        }

        it("parses single and multiple order expressions") {
            parse("findByAgeOrderByNameDesc").orderBy shouldBe
                listOf(QueryOrder("name", SortDirection.DESC))
            parse("findByAgeOrderByNameAscEmailDesc").orderBy shouldBe listOf(
                QueryOrder("name", SortDirection.ASC),
                QueryOrder("email", SortDirection.DESC),
            )
        }

        it("supports a predicate-free limited query with ordering") {
            val query = parse("findTop3ByOrderByAgeDesc")

            query.predicate.disjuncts shouldBe emptyList()
            query.orderBy shouldBe listOf(QueryOrder("age", SortDirection.DESC))
            query.limit shouldBe 3
        }
    }
})

private fun PredicateGroup.single(): QueryPredicate = disjuncts.single().predicates.single()

private data class ParserUser(
    val id: Long,
    val name: String,
    val email: String,
    val age: Int,
    val active: Boolean,
    val tags: List<String>,
    val location: ParserLocation,
    val address: ParserAddress,
    val userAddress: ParserAddress,
)

private data class ParserAddress(val city: String)
private data class ParserLocation(val value: String)
