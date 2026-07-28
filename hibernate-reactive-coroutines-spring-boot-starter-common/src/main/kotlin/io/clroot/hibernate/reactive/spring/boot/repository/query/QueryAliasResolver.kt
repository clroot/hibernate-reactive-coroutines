package io.clroot.hibernate.reactive.spring.boot.repository.query

import java.util.Locale

/**
 * `@Query`로 선언된 HQL에서 루트 엔티티의 별칭을 찾아냅니다.
 *
 * 동적 `Sort`를 적용하려면 정렬 프로퍼티 앞에 붙일 별칭이 필요합니다.
 * 별칭을 확신할 수 없으면 null을 반환하여 호출자가 정렬을 조용히 무시하는 대신
 * 명확히 실패하도록 합니다.
 */
internal object QueryAliasResolver {

    private const val IDENTIFIER = "[\\p{L}_$][\\p{L}\\p{N}_$]*"

    /** `SELECT e FROM ...` / `SELECT DISTINCT e FROM ...` 형태의 단순 선택 별칭 */
    private val SELECTED_ALIAS = Regex(
        "^\\s*SELECT\\s+(?:DISTINCT\\s+)?($IDENTIFIER)\\s+FROM\\s",
        RegexOption.IGNORE_CASE,
    )

    /** `FROM Entity e` / `FROM Entity AS e` 형태의 선언 별칭 */
    private val DECLARED_ALIAS = Regex(
        "\\bFROM\\s+$IDENTIFIER(?:\\.$IDENTIFIER)*\\s+(?:AS\\s+)?($IDENTIFIER)",
        RegexOption.IGNORE_CASE,
    )

    /** 별칭 자리에 올 수 있지만 별칭이 아닌 예약어들 */
    private val RESERVED_WORDS = setOf(
        "WHERE", "ORDER", "GROUP", "HAVING", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL",
        "CROSS", "ON", "UNION", "INTERSECT", "EXCEPT", "FETCH", "SET", "AS",
    )

    /**
     * 루트 별칭을 반환합니다. 판별할 수 없으면 null을 반환합니다.
     */
    fun resolve(query: String): String? {
        val normalizedQuery = query.trim()

        SELECTED_ALIAS.find(normalizedQuery)
            ?.groupValues
            ?.get(1)
            ?.takeIf(::isAlias)
            ?.let { return it }

        return DECLARED_ALIAS.find(normalizedQuery)
            ?.groupValues
            ?.get(1)
            ?.takeIf(::isAlias)
    }

    private fun isAlias(candidate: String): Boolean =
        candidate.uppercase(Locale.ROOT) !in RESERVED_WORDS
}
