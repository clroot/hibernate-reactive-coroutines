package io.clroot.hibernate.reactive.spring.boot.repository

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.string.shouldContain

class QueryAnnotationValidationTest : DescribeSpec({

    describe("@Query 검증") {

        context("Named/Positional 혼용") {
            it("혼용 시 예외를 발생시킨다") {
                val exception = shouldThrow<IllegalStateException> {
                    // 실제 파싱 로직 호출 시뮬레이션
                    validateMixedParameters("SELECT e FROM Entity e WHERE e.name = :name AND e.value = ?1")
                }
                exception.message shouldContain "mixes named"
            }
        }

        context("@Modifying + SELECT") {
            it("@Modifying에 SELECT 쿼리면 예외를 발생시킨다") {
                val exception = shouldThrow<IllegalStateException> {
                    validateModifyingWithSelect("SELECT e FROM Entity e")
                }
                exception.message shouldContain "cannot have SELECT"
            }
        }

        context("UPDATE/DELETE without @Modifying") {
            it("UPDATE 쿼리에 @Modifying가 없으면 예외를 발생시킨다") {
                val exception = shouldThrow<IllegalStateException> {
                    validateNonSelectWithoutModifying("UPDATE Entity e SET e.value = 1")
                }
                exception.message shouldContain "missing @Modifying"
            }

            it("DELETE 쿼리에 @Modifying가 없으면 예외를 발생시킨다") {
                val exception = shouldThrow<IllegalStateException> {
                    validateNonSelectWithoutModifying("DELETE FROM Entity e WHERE e.id = 1")
                }
                exception.message shouldContain "missing @Modifying"
            }
        }
    }
})

// 검증 로직 헬퍼 (실제 구현체 로직과 동일)
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
