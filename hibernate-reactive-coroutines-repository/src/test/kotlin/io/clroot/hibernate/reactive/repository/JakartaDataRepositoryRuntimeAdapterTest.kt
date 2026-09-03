package io.clroot.hibernate.reactive.repository

import io.clroot.hibernate.reactive.repository.query.derived.QueryOrder
import io.clroot.hibernate.reactive.repository.query.derived.SortDirection
import io.clroot.hibernate.reactive.repository.runtime.RepositoryPageRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.data.Order
import jakarta.data.Sort
import jakarta.data.page.Page
import jakarta.data.page.PageRequest
import java.util.NoSuchElementException

class JakartaDataRepositoryRuntimeAdapterTest : DescribeSpec({
    describe("Jakarta Data arguments") {
        it("extracts one-based offset pagination and combines Sort and Order values") {
            val request = PageRequest.ofPage(3, 20, false)
            val order = Order.by<User>(Sort.ascIgnoreCase("name"), Sort.desc("id"))

            val adapted = JakartaDataRepositoryRuntimeAdapter.adaptArguments(
                listOf("active", Sort.asc<User>("active"), request, order),
            )

            adapted.queryArguments.shouldContainExactly("active")
            adapted.pageRequest?.offset shouldBe 40L
            adapted.pageRequest?.pageSize shouldBe 20
            JakartaDataRepositoryRuntimeAdapter.shouldRequestTotal(checkNotNull(adapted.pageRequest)) shouldBe false
            adapted.sort.shouldContainExactly(
                QueryOrder("active", SortDirection.ASC),
                QueryOrder("name", SortDirection.ASC, ignoreCase = true),
                QueryOrder("id", SortDirection.DESC),
            )
            adapted.hasSortParameter shouldBe true
        }

        it("recognizes Sort vararg arrays as special parameters") {
            val sorts = arrayOf(Sort.asc<User>("name"), Sort.desc<User>("id"))

            val adapted = JakartaDataRepositoryRuntimeAdapter.adaptArguments(listOf("query", sorts))

            adapted.queryArguments.shouldContainExactly("query")
            adapted.sort.map(QueryOrder::property).shouldContainExactly("name", "id")
        }

        it("rejects cursor requests because the runtime currently supports offset pagination") {
            val cursor = PageRequest.Cursor.forKey(10L)
            val request = PageRequest.afterCursor(cursor, 1, 10, true)

            shouldThrow<IllegalArgumentException> {
                JakartaDataRepositoryRuntimeAdapter.adaptArguments(listOf(request))
            }.message shouldContain "Cursor-based"
        }
    }

    describe("Jakarta Data Page results") {
        it("exposes totals and navigation for total-enabled requests") {
            val request = PageRequest.ofPage(2, 2, true)
            val page = JakartaDataRepositoryRuntimeAdapter.createPage(
                content = listOf("c", "d"),
                request = RepositoryPageRequest(
                    offset = 2,
                    pageSize = 2,
                    context = request,
                ),
                totalElements = 5,
            ) as Page<*>

            page.content().shouldContainExactly("c", "d")
            page.hasTotals() shouldBe true
            page.totalElements() shouldBe 5L
            page.totalPages() shouldBe 3L
            page.hasPrevious() shouldBe true
            page.hasNext() shouldBe true
            page.previousPageRequest().page() shouldBe 1L
            page.nextPageRequest().page() shouldBe 3L
        }

        it("hides totals when PageRequest disables them") {
            val request = PageRequest.ofPage(1, 2, false)
            val page = JakartaDataRepositoryRuntimeAdapter.createPage(
                content = listOf("a", "b", "lookahead"),
                request = RepositoryPageRequest(
                    offset = 0,
                    pageSize = 2,
                    context = request,
                ),
                totalElements = 2,
            ) as Page<*>

            page.content().shouldContainExactly("a", "b")
            page.hasTotals() shouldBe false
            page.hasNext() shouldBe true
            shouldThrow<IllegalStateException> { page.totalElements() }
            shouldThrow<IllegalStateException> { page.totalPages() }
        }

        it("rejects navigation beyond a known boundary") {
            val request = PageRequest.ofPage(1, 10, true)
            val page = JakartaDataRepositoryRuntimeAdapter.createPage(
                content = listOf("only"),
                request = RepositoryPageRequest(offset = 0, pageSize = 10, context = request),
                totalElements = 1,
            ) as Page<*>

            page.hasPrevious() shouldBe false
            page.hasNext() shouldBe false
            shouldThrow<NoSuchElementException> { page.previousPageRequest() }
            shouldThrow<NoSuchElementException> { page.nextPageRequest() }
        }
    }
}) {
    data class User(val id: Long, val name: String, val active: Boolean)
}
