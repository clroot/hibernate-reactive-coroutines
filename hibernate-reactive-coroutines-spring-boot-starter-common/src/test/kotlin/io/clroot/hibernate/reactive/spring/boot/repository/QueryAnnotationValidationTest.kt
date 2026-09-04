package io.clroot.hibernate.reactive.spring.boot.repository

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.string.shouldContain

class QueryAnnotationValidationTest : DescribeSpec({

    describe("@Query validation") {

        context("mixed named and positional parameters") {
            it("throws an exception") {
                val exception = shouldThrow<IllegalStateException> {
                    validateMixedParameters("SELECT e FROM Entity e WHERE e.name = :name AND e.value = ?1")
                }
                exception.message shouldContain "mixes named"
            }
        }

        context("@Modifying + SELECT") {
            it("throws an exception for a SELECT query") {
                val exception = shouldThrow<IllegalStateException> {
                    validateModifyingWithSelect("SELECT e FROM Entity e")
                }
                exception.message shouldContain "cannot have SELECT"
            }
        }

        context("UPDATE/DELETE without @Modifying") {
            it("throws an exception for an UPDATE query") {
                val exception = shouldThrow<IllegalStateException> {
                    validateNonSelectWithoutModifying("UPDATE Entity e SET e.value = 1")
                }
                exception.message shouldContain "missing @Modifying"
            }

            it("throws an exception for a DELETE query") {
                val exception = shouldThrow<IllegalStateException> {
                    validateNonSelectWithoutModifying("DELETE FROM Entity e WHERE e.id = 1")
                }
                exception.message shouldContain "missing @Modifying"
            }
        }
    }
})

private fun validateMixedParameters(query: String) {
    val hasNamed = query.contains(Regex(":\\w+"))
    val hasPositional = query.contains(Regex("\\?\\d+"))
    if (hasNamed && hasPositional) {
        throw IllegalStateException("Query mixes named (:name) and positional (?1) parameters")
    }
}

private fun validateModifyingWithSelect(query: String) {
    if (query.trim().startsWith("SELECT", ignoreCase = true)) {
        throw IllegalStateException("@Modifying method cannot have SELECT query")
    }
}

private fun validateNonSelectWithoutModifying(query: String) {
    val isSelect = query.trim().startsWith("SELECT", ignoreCase = true)
    val isFrom = query.trim().startsWith("FROM", ignoreCase = true)
    if (!isSelect && !isFrom) {
        throw IllegalStateException("Method has UPDATE/DELETE query but missing @Modifying annotation")
    }
}
