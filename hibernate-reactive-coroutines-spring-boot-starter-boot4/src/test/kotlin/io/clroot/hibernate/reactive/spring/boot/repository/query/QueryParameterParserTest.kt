package io.clroot.hibernate.reactive.spring.boot.repository.query

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class QueryParameterParserTest : DescribeSpec({
    describe("QueryParameterParser") {
        it("extracts unique named parameters outside literals and comments") {
            QueryParameterParser.parse(
                "SELECT e FROM User e WHERE e.name = :name " +
                        "AND e.note = ':ignored' /* :alsoIgnored */ OR e.alias = :name",
            ) shouldBe QueryParameters(
                style = ParameterStyle.NAMED,
                names = listOf("name"),
            )
        }

        it("extracts the positional parameters actually referenced") {
            QueryParameterParser.parse(
                "SELECT e FROM User e WHERE e.first = ?2 AND e.second = ?1",
            ) shouldBe QueryParameters(
                style = ParameterStyle.POSITIONAL,
                positions = listOf(2, 1),
            )
        }

        it("does not treat a PostgreSQL cast as a named parameter") {
            QueryParameterParser.parse(
                "SELECT created_at::text FROM users WHERE status = :status",
            ) shouldBe QueryParameters(
                style = ParameterStyle.NAMED,
                names = listOf("status"),
            )
        }

        it("rejects dollar quotes that Hibernate cannot parameter-parse") {
            shouldThrow<IllegalStateException> {
                QueryParameterParser.parse(
                    "SELECT \$\$:ignored ?1\$\$ FROM users WHERE status = :status",
                )
            }
        }

        it("keeps embedded dollar tags inside PostgreSQL identifiers") {
            QueryParameterParser.parse(
                "SELECT foo\$tag\$bar FROM users WHERE status = :status",
            ) shouldBe QueryParameters(
                style = ParameterStyle.NAMED,
                names = listOf("status"),
            )
        }

        it("rejects nested block comments that Hibernate cannot parameter-parse") {
            shouldThrow<IllegalStateException> {
                QueryParameterParser.parse(
                    "SELECT * FROM users /* outer /* :nested */ ?2 */ WHERE status = :status",
                )
            }
        }

        it("rejects an unmatched block-comment terminator") {
            shouldThrow<IllegalStateException> {
                QueryParameterParser.parse(
                    "SELECT * FROM users */ WHERE status = :status",
                )
            }
        }

        it("rejects line comments that do not have consistent Hibernate parser semantics") {
            shouldThrow<IllegalStateException> {
                QueryParameterParser.parse(
                    "SELECT e FROM User e WHERE e.x--e.y > :min",
                )
            }
        }

        it("rejects an unterminated dollar quote") {
            shouldThrow<IllegalStateException> {
                QueryParameterParser.parse("SELECT \$body\$:ignored FROM users")
            }
        }

        it("rejects mixed parameter styles") {
            shouldThrow<IllegalStateException> {
                QueryParameterParser.parse(
                    "SELECT e FROM User e WHERE e.first = :first AND e.second = ?2",
                )
            }
        }

        it("rejects non-contiguous positional labels") {
            shouldThrow<IllegalStateException> {
                QueryParameterParser.parse(
                    "SELECT e FROM User e WHERE e.second = ?2",
                )
            }
        }

        it("rejects unlabeled positional parameters") {
            shouldThrow<IllegalStateException> {
                QueryParameterParser.parse(
                    "SELECT e FROM User e WHERE e.id = ?",
                )
            }
        }
    }
})
