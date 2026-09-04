package io.clroot.hibernate.reactive.test.recorder

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.Collections

/** Records HQL queries for test assertions. */
class HqlRecorder {
    private val recordedQueries = Collections.synchronizedList(mutableListOf<RecordedQuery>())

    fun record(query: RecordedQuery) {
        recordedQueries.add(query)
    }

    fun getRecordedQueries(): List<RecordedQuery> = recordedQueries.toList()

    fun getLastQuery(): RecordedQuery? = recordedQueries.lastOrNull()

    fun getQueriesByType(type: QueryType): List<RecordedQuery> =
        recordedQueries.filter { it.queryType == type }

    fun clear() {
        recordedQueries.clear()
    }

    fun queryCount(): Int = recordedQueries.size

    fun assertQueryCount(expected: Int) {
        recordedQueries shouldHaveSize expected
    }

    fun assertLastQueryContains(substring: String) {
        val last = getLastQuery()
            ?: throw AssertionError("No queries recorded")
        last.hql shouldContain substring
    }

    fun assertLastQueryEquals(expected: String) {
        val last = getLastQuery()
            ?: throw AssertionError("No queries recorded")
        last.hql shouldBe expected
    }

    fun assertNoQueriesContaining(substring: String) {
        val matching = recordedQueries.filter { it.hql.contains(substring) }
        if (matching.isNotEmpty()) {
            throw AssertionError(
                "Expected no queries containing '$substring', but found ${matching.size}: " +
                        matching.map { it.hql },
            )
        }
    }

    fun assertQuerySequence(vararg patterns: String) {
        recordedQueries.size shouldBe patterns.size
        patterns.forEachIndexed { index, pattern ->
            recordedQueries[index].hql shouldContain pattern
        }
    }

    fun assertQueryCountByType(type: QueryType, expected: Int) {
        getQueriesByType(type) shouldHaveSize expected
    }
}
