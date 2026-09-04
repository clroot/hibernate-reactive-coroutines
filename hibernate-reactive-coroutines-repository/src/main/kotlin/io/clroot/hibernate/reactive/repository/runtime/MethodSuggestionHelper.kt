package io.clroot.hibernate.reactive.repository.runtime

/** Suggests likely repository methods for unknown method names. */
internal object MethodSuggestionHelper {
    /** Avoid suggestions too dissimilar to be useful. */
    private const val MAX_SUGGESTION_DISTANCE = 5

    /** Keep error messages actionable. */
    private const val MAX_SUGGESTIONS = 3

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

    fun findSimilarMethods(methodName: String, availableMethods: Set<String>): List<String> {
        return availableMethods
            .map { it to levenshteinDistance(methodName, it) }
            .filter { it.second <= MAX_SUGGESTION_DISTANCE }
            .sortedBy { it.second }
            .take(MAX_SUGGESTIONS)
            .map { it.first }
    }

    fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length

        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }

        return dp[m][n]
    }
}
