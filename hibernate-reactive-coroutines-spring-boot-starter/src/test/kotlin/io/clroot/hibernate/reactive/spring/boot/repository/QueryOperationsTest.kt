package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.springframework.data.domain.Sort

class QueryOperationsTest : DescribeSpec({

    val operations = QueryOperations(User::class.java, mockk<TransactionalAwareSessionProvider>())

    describe("dynamic sort") {
        it("resolves a known nested property") {
            val sort = Sort.by(Sort.Direction.ASC, "address.city")

            operations.buildSortClause(sort) shouldBe "e.address.city ASC"
        }

        it("rejects a property that is not part of the entity model") {
            val sort = Sort.by("name) desc, (select count(e2) from User e2")

            shouldThrow<IllegalArgumentException> {
                operations.buildSortClause(sort)
            }
        }
    }
}) {
    data class User(val name: String, val address: Address)
    data class Address(val city: String)
}
