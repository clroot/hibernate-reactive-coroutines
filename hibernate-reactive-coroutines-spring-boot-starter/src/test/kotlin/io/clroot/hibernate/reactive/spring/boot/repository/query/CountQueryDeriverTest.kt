package io.clroot.hibernate.reactive.spring.boot.repository.query

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class CountQueryDeriverTest : DescribeSpec({
    describe("CountQueryDeriver") {
        it("does not treat keyword-shaped path segments as clauses") {
            CountQueryDeriver.derive(
                "SELECT e FROM User e " +
                        "WHERE e.order = 1 AND e.join = true AND e.union = false ORDER BY e.id",
            ) shouldBe
                    "SELECT COUNT(*) FROM User e " +
                    "WHERE e.order = 1 AND e.join = true AND e.union = false"
        }

        it("keeps dollar signs inside identifiers") {
            CountQueryDeriver.derive(
                "SELECT e FROM User e WHERE e.order\$by = true ORDER BY e.id",
            ) shouldBe
                    "SELECT COUNT(*) FROM User e WHERE e.order\$by = true"
        }

        it("does not treat unqualified soft keywords as clauses") {
            CountQueryDeriver.derive(
                "FROM Thing e WHERE order = by AND group = by AND join = fetch",
            ) shouldBe
                    "SELECT COUNT(*) FROM Thing e WHERE order = by AND group = by AND join = fetch"
        }

        it("supports soft keywords as entity aliases") {
            CountQueryDeriver.derive(
                "SELECT join FROM User join WHERE join.active = true ORDER BY join.id",
            ) shouldBe "SELECT COUNT(*) FROM User join WHERE join.active = true"
            CountQueryDeriver.derive(
                "FROM User join WHERE join.active = true ORDER BY join.id",
            ) shouldBe "SELECT COUNT(*) FROM User join WHERE join.active = true"
            CountQueryDeriver.derive(
                "FROM User AS select WHERE select.active = true ORDER BY select.id",
            ) shouldBe "SELECT COUNT(*) FROM User AS select WHERE select.active = true"
        }

        it("handles aliasless root queries without hiding structural clauses") {
            CountQueryDeriver.derive(
                "FROM User WHERE active = true ORDER BY id",
            ) shouldBe "SELECT COUNT(*) FROM User WHERE active = true"

            listOf(
                "FROM User GROUP BY active",
                "FROM User JOIN FETCH roles",
                "FROM User UNION FROM User",
            ).forEach { query ->
                val error = shouldThrow<IllegalStateException> {
                    CountQueryDeriver.derive(query)
                }

                error.message shouldContain "declare countQuery explicitly"
            }
        }

        it("rejects implicit paths in aliasless predicates") {
            listOf(
                "FROM Employee WHERE department.name = :name",
                "FROM Employee WHERE roles[0].name = :name",
                "FROM Employee WHERE element(roles).name = :name",
                "FROM Employee WHERE department.`name` = :name",
            ).forEach { query ->
                val error = shouldThrow<IllegalStateException> {
                    CountQueryDeriver.derive(query)
                }

                error.message shouldContain "implicit join"
            }
        }

        it("does not treat keyword-shaped parameters as clauses") {
            CountQueryDeriver.derive(
                "FROM User e WHERE e.rank = :order + :by " +
                        "AND e.score = :group + :by AND e.other = :join + :fetch",
            ) shouldBe
                    "SELECT COUNT(*) FROM User e WHERE e.rank = :order + :by " +
                    "AND e.score = :group + :by AND e.other = :join + :fetch"
        }

        it("keeps escaped double-quoted literals intact") {
            CountQueryDeriver.derive(
                """SELECT e FROM User e WHERE e.note = "escaped \" ORDER BY still string" ORDER BY e.id""",
            ) shouldBe
                    """SELECT COUNT(*) FROM User e WHERE e.note = "escaped \" ORDER BY still string""""
        }

        it("ignores keywords inside backtick-quoted identifiers") {
            CountQueryDeriver.derive(
                "SELECT e FROM `Union` e ORDER BY e.id",
            ) shouldBe "SELECT COUNT(*) FROM `Union` e"
        }

        it("rejects every top-level set operation") {
            listOf(
                "SELECT e FROM User e UNION ALL SELECT e FROM User e",
                "SELECT e FROM User e INTERSECT SELECT e FROM User e",
                "SELECT e FROM User e EXCEPT SELECT e FROM User e",
                "FROM User e UNION FROM User e",
                "SELECT e FROM User e INTERSECT WHERE e.active = true",
                "SELECT e FROM User e UNION ALL (SELECT e FROM User e)",
                "SELECT e FROM User e EXCEPT /* comment */ (SELECT e FROM User e)",
                "SELECT e FROM User e UNION ORDER BY id",
            ).forEach { query ->
                val error = shouldThrow<IllegalStateException> {
                    CountQueryDeriver.derive(query)
                }

                error.message shouldContain "declare countQuery explicitly"
            }
        }

        it("rejects a parameterized order by") {
            val error = shouldThrow<IllegalStateException> {
                CountQueryDeriver.derive(
                    "SELECT e FROM User e WHERE e.active = true " +
                            "ORDER BY CASE WHEN :priority = true THEN 0 ELSE 1 END",
                )
            }

            error.message shouldContain "parameterized ORDER BY"
        }

        it("rejects projections that may add an implicit join") {
            val error = shouldThrow<IllegalStateException> {
                CountQueryDeriver.derive(
                    "SELECT e.department.name FROM Employee e ORDER BY e.id",
                )
            }

            error.message shouldContain "declare countQuery explicitly"
        }

        it("rejects order paths that may add an implicit join") {
            val error = shouldThrow<IllegalStateException> {
                CountQueryDeriver.derive(
                    "SELECT e FROM Employee e ORDER BY e.department.name",
                )
            }

            error.message shouldContain "implicit join in ORDER BY"
        }

        it("rejects predicate paths that may add an implicit join") {
            listOf(
                "SELECT e FROM Employee e WHERE e.department.name = :name",
                "SELECT e FROM Employee e WHERE lower(e.department.name) = :name",
                "SELECT e FROM Employee e WHERE e.roles[0].name = :name",
                "SELECT e FROM Employee e WHERE element(e.roles).name = :name",
                "SELECT e FROM Employee e WHERE e.`department`.name = :name",
            ).forEach { query ->
                val error = shouldThrow<IllegalStateException> {
                    CountQueryDeriver.derive(query)
                }

                error.message shouldContain "implicit join"
            }
        }

        it("allows simple root properties with standard ordering modifiers") {
            CountQueryDeriver.derive(
                "SELECT e FROM Employee e ORDER BY e.name DESC NULLS LAST, id ASC",
            ) shouldBe "SELECT COUNT(*) FROM Employee e"
        }

        it("rejects complex order expressions that may add implicit joins") {
            listOf(
                "SELECT e FROM Employee e ORDER BY lower(e.name)",
                "SELECT e FROM Employee e ORDER BY treat(e.department AS Department).name",
                "SELECT e FROM Employee e ORDER BY e.roles[0].name",
                "SELECT e FROM Employee e ORDER BY element(e.roles).name",
            ).forEach { query ->
                val error = shouldThrow<IllegalStateException> {
                    CountQueryDeriver.derive(query)
                }

                error.message shouldContain "complex or implicit join in ORDER BY"
            }
        }

        it("rejects joins with backtick-quoted targets") {
            listOf(
                "SELECT e FROM User e JOIN `Role` r ON r.user = e",
                "SELECT e FROM User e LEFT JOIN `Role` r ON r.user = e",
                "SELECT e FROM User e CROSS JOIN `Role` r",
            ).forEach { query ->
                val error = shouldThrow<IllegalStateException> {
                    CountQueryDeriver.derive(query)
                }

                error.message shouldContain "JOIN"
            }
        }

        it("rejects comma-separated query roots") {
            listOf(
                "SELECT e FROM User e, Role r WHERE r.user = e",
                "FROM User e , Role r WHERE r.user = e",
            ).forEach { query ->
                val error = shouldThrow<IllegalStateException> {
                    CountQueryDeriver.derive(query)
                }

                error.message shouldContain "multiple query roots"
            }
        }

        it("rejects a trailing select clause") {
            val error = shouldThrow<IllegalStateException> {
                CountQueryDeriver.derive(
                    "FROM Book b SELECT b.title ORDER BY b.id",
                )
            }

            error.message shouldContain "trailing SELECT"
        }

        it("rejects pagination clauses") {
            listOf(
                "SELECT e FROM User e LIMIT 10",
                "SELECT e FROM User e OFFSET 5",
                "SELECT e FROM User e FETCH FIRST 10 ROWS ONLY",
            ).forEach { query ->
                val error = shouldThrow<IllegalStateException> {
                    CountQueryDeriver.derive(query)
                }

                error.message shouldContain "declare countQuery explicitly"
            }
        }

        it("requires an exact query prefix") {
            shouldThrow<IllegalStateException> {
                CountQueryDeriver.derive("FROMAGE Thing")
            }
        }

        it("rejects malformed lexical and parenthesis states") {
            listOf(
                "SELECT e FROM User e WHERE (e.active = true",
                "SELECT e FROM User e WHERE e.active = true)",
                "SELECT e FROM User e WHERE e.note = 'unterminated",
                """SELECT e FROM User e WHERE e.note = "unterminated""",
                "SELECT e FROM `unterminated",
                "SELECT e FROM User e /* unterminated",
                "SELECT e FROM User e WHERE e.note = \$\$ ORDER BY marker \$\$ ORDER BY e.id",
                "SELECT e FROM User e /* outer /* nested */ */ ORDER BY e.id",
                "SELECT e FROM User e */ ORDER BY e.id",
                "SELECT e FROM User e WHERE e.x--e.y > :min ORDER BY e.id",
            ).forEach { query ->
                val error = shouldThrow<IllegalStateException> {
                    CountQueryDeriver.derive(query)
                }

                error.message shouldContain "cannot parse the query"
            }
        }
    }
})
