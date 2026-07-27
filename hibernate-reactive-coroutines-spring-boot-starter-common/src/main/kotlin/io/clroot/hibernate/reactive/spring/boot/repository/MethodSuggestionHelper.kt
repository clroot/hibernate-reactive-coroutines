package io.clroot.hibernate.reactive.spring.boot.repository

/**
 * 알 수 없는 메서드에 대한 유사 메서드 추천을 담당하는 헬퍼.
 *
 * Levenshtein 거리(편집 거리) 알고리즘을 사용하여
 * 오타가 있는 메서드명에 대해 유사한 메서드를 추천합니다.
 */
internal object MethodSuggestionHelper {
    /** 유사 메서드 추천 시 최대 편집 거리 */
    private const val MAX_SUGGESTION_DISTANCE = 5

    /** 최대 추천 개수 */
    private const val MAX_SUGGESTIONS = 3

    /**
     * 알 수 없는 메서드에 대한 에러 메시지를 생성합니다.
     * 유사한 메서드가 있으면 추천합니다.
     *
     * @param methodName 찾을 수 없는 메서드명
     * @param availableMethods 사용 가능한 메서드 목록
     * @return 에러 메시지 (유사 메서드 추천 포함)
     */
    fun buildUnknownMethodError(methodName: String, availableMethods: Set<String>): String {
        val suggestions = findSimilarMethods(methodName, availableMethods)

        return buildString {
            append("Unknown method: $methodName")
            if (suggestions.isNotEmpty()) {
                append(". Did you mean: ")
                append(suggestions.joinToString(", ") { "'$it'" })
                append("?")
            }
        }
    }

    /**
     * 주어진 메서드명과 유사한 메서드들을 찾습니다.
     *
     * @param methodName 검색할 메서드명
     * @param availableMethods 사용 가능한 메서드 목록
     * @return 유사한 메서드명 목록 (편집 거리 순으로 정렬)
     */
    fun findSimilarMethods(methodName: String, availableMethods: Set<String>): List<String> {
        return availableMethods
            .map { it to levenshteinDistance(methodName, it) }
            .filter { it.second <= MAX_SUGGESTION_DISTANCE }
            .sortedBy { it.second }
            .take(MAX_SUGGESTIONS)
            .map { it.first }
    }

    /**
     * 두 문자열 사이의 Levenshtein 거리(편집 거리)를 계산합니다.
     *
     * Levenshtein 거리는 한 문자열을 다른 문자열로 변환하는 데 필요한
     * 최소 편집 횟수(삽입, 삭제, 치환)를 의미합니다.
     *
     * @param s1 첫 번째 문자열
     * @param s2 두 번째 문자열
     * @return 편집 거리
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length

        // dp[i][j] = s1[0..i-1]과 s2[0..j-1] 사이의 편집 거리
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,       // 삭제
                    dp[i][j - 1] + 1,       // 삽입
                    dp[i - 1][j - 1] + cost // 치환
                )
            }
        }

        return dp[m][n]
    }
}
