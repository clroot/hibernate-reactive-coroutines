package io.clroot.hibernate.reactive.spring.boot.repository

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class PaginationOffsetTest : DescribeSpec({
    describe("Hibernate first-result conversion") {
        it("accepts offsets within the Int range") {
            0L.toHibernateFirstResult() shouldBe 0
            Int.MAX_VALUE.toLong().toHibernateFirstResult() shouldBe Int.MAX_VALUE
        }

        it("rejects offsets that would overflow Int") {
            val error = shouldThrow<IllegalArgumentException> {
                (Int.MAX_VALUE.toLong() + 1).toHibernateFirstResult()
            }

            error.message shouldContain "keyset pagination"
        }

        it("rejects negative offsets") {
            shouldThrow<IllegalArgumentException> { (-1L).toHibernateFirstResult() }
        }
    }
})
