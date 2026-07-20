package io.clroot.hibernate.reactive.spring.boot.repository.query

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.data.domain.Sort
import org.springframework.data.repository.query.parser.PartTree

class PartTreeHqlBuilderSecurityTest : DescribeSpec({

    describe("property paths") {
        it("uses the complete nested property path") {
            val partTree = PartTree("findByAddressCity", User::class.java)

            val result = PartTreeHqlBuilder("User", partTree).build()

            result.hql shouldBe "FROM User e WHERE e.address.city = :p0"
        }

        it("rejects a sort property that can be interpreted as HQL") {
            val partTree = PartTree("findAllByName", User::class.java)
            val maliciousSort = Sort.by("name) desc, (select count(e2) from User e2")

            shouldThrow<IllegalArgumentException> {
                PartTreeHqlBuilder("User", partTree).buildWithSort(maliciousSort)
            }
        }
    }
}) {
    data class User(val name: String, val address: Address)
    data class Address(val city: String)
}
