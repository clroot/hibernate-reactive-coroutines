package io.clroot.hibernate.reactive.test.recorder

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.Collections

/**
 * HQL 쿼리를 기록하고 검증하는 유틸리티.
 *
 * 테스트에서 생성된 HQL 쿼리를 캡처하여 검증할 수 있습니다.
 * 쿼리 최적화, N+1 문제 감지, 예상 쿼리 검증 등에 활용됩니다.
 *
 * 사용 예:
 * ```kotlin
 * hqlRecorder.clear()
 * repository.findByName("test")
 *
 * hqlRecorder.assertQueryCount(1)
 * hqlRecorder.assertLastQueryContains("WHERE e.name = :p0")
 * ```
 */
class HqlRecorder {
    private val recordedQueries = Collections.synchronizedList(mutableListOf<RecordedQuery>())

    /**
     * 쿼리를 기록합니다.
     */
    fun record(query: RecordedQuery) {
        recordedQueries.add(query)
    }

    /**
     * 기록된 모든 쿼리를 반환합니다.
     */
    fun getRecordedQueries(): List<RecordedQuery> = recordedQueries.toList()

    /**
     * 마지막으로 기록된 쿼리를 반환합니다.
     */
    fun getLastQuery(): RecordedQuery? = recordedQueries.lastOrNull()

    /**
     * 특정 타입의 쿼리만 필터링하여 반환합니다.
     */
    fun getQueriesByType(type: QueryType): List<RecordedQuery> =
        recordedQueries.filter { it.queryType == type }

    /**
     * 기록을 초기화합니다.
     */
    fun clear() {
        recordedQueries.clear()
    }

    /**
     * 기록된 쿼리 수를 반환합니다.
     */
    fun queryCount(): Int = recordedQueries.size

    // === Assertion Helpers ===

    /**
     * 기록된 쿼리 수를 검증합니다.
     */
    fun assertQueryCount(expected: Int) {
        recordedQueries shouldHaveSize expected
    }

    /**
     * 마지막 쿼리가 특정 문자열을 포함하는지 검증합니다.
     */
    fun assertLastQueryContains(substring: String) {
        val last = getLastQuery()
            ?: throw AssertionError("No queries recorded")
        last.hql shouldContain substring
    }

    /**
     * 마지막 쿼리가 특정 문자열과 일치하는지 검증합니다.
     */
    fun assertLastQueryEquals(expected: String) {
        val last = getLastQuery()
            ?: throw AssertionError("No queries recorded")
        last.hql shouldBe expected
    }

    /**
     * 어떤 쿼리도 특정 문자열을 포함하지 않는지 검증합니다.
     */
    fun assertNoQueriesContaining(substring: String) {
        val matching = recordedQueries.filter { it.hql.contains(substring) }
        if (matching.isNotEmpty()) {
            throw AssertionError(
                "Expected no queries containing '$substring', but found ${matching.size}: " +
                        matching.map { it.hql },
            )
        }
    }

    /**
     * 쿼리 시퀀스가 예상 패턴과 일치하는지 검증합니다.
     */
    fun assertQuerySequence(vararg patterns: String) {
        recordedQueries.size shouldBe patterns.size
        patterns.forEachIndexed { index, pattern ->
            recordedQueries[index].hql shouldContain pattern
        }
    }

    /**
     * 특정 타입의 쿼리 수를 검증합니다.
     */
    fun assertQueryCountByType(type: QueryType, expected: Int) {
        getQueriesByType(type) shouldHaveSize expected
    }
}
