package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.repository.query.derived.SortDirection
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Slice
import org.springframework.data.domain.Sort

class SpringRepositoryRuntimeAdapterTest : DescribeSpec({
    describe("Spring repository runtime adapter") {
        it("removes Pageable from query arguments and preserves its sort metadata") {
            val pageable = PageRequest.of(
                2,
                20,
                Sort.by(Sort.Order.desc("name").ignoreCase()),
            )

            val adapted = SpringRepositoryRuntimeAdapter.adaptArguments(listOf("active", pageable))

            adapted.queryArguments.shouldContainExactly("active")
            adapted.pageRequest?.offset shouldBe 40L
            adapted.pageRequest?.pageSize shouldBe 20
            adapted.pageRequest?.context shouldBe pageable
            adapted.sort.single().property shouldBe "name"
            adapted.sort.single().direction shouldBe SortDirection.DESC
            adapted.sort.single().ignoreCase shouldBe true
        }

        it("distinguishes an explicit unsorted Sort parameter") {
            val adapted = SpringRepositoryRuntimeAdapter.adaptArguments(listOf(Sort.unsorted()))

            adapted.queryArguments shouldBe emptyList()
            adapted.sort shouldBe emptyList()
            adapted.hasSortParameter shouldBe true
        }

        it("constructs Spring-native Page and Slice results") {
            val pageable = PageRequest.of(0, 2)
            val request = SpringRepositoryRuntimeAdapter
                .adaptArguments(listOf(pageable))
                .pageRequest!!

            val page = SpringRepositoryRuntimeAdapter.createPage(listOf("a"), request, 3) as Page<*>
            val slice = SpringRepositoryRuntimeAdapter.createSlice(listOf("a"), request, true) as Slice<*>

            page.content.shouldContainExactly("a")
            page.totalElements shouldBe 3
            slice.content.shouldContainExactly("a")
            slice.hasNext() shouldBe true
        }
    }
})
