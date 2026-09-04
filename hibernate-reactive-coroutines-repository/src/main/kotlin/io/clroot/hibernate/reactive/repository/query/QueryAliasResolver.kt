package io.clroot.hibernate.reactive.repository.query

import java.util.Locale

/**
 * Resolves the root entity alias from HQL declared with `@Query`.
 *
 * Dynamic sorting requires an alias to qualify property paths. Returning `null` for ambiguous
 * queries lets the caller reject unsafe sorting instead of silently changing query semantics.
 */
internal object QueryAliasResolver {

    private const val IDENTIFIER = "[\\p{L}_$][\\p{L}\\p{N}_$]*"

    /** Simple selected aliases such as `SELECT e FROM ...` or `SELECT DISTINCT e FROM ...`. */
    private val SELECTED_ALIAS = Regex(
        "^\\s*SELECT\\s+(?:DISTINCT\\s+)?($IDENTIFIER)\\s+FROM\\s",
        RegexOption.IGNORE_CASE,
    )

    /** Aliases declared as `FROM Entity e` or `FROM Entity AS e`. */
    private val DECLARED_ALIAS = Regex(
        "\\bFROM\\s+$IDENTIFIER(?:\\.$IDENTIFIER)*\\s+(?:AS\\s+)?($IDENTIFIER)",
        RegexOption.IGNORE_CASE,
    )

    /** Keywords that can follow `FROM` but must not be interpreted as aliases. */
    private val RESERVED_WORDS = setOf(
        "WHERE", "ORDER", "GROUP", "HAVING", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL",
        "CROSS", "ON", "UNION", "INTERSECT", "EXCEPT", "FETCH", "SET", "AS",
    )

    /** Returns the root alias, or `null` when it cannot be determined safely. */
    public fun resolve(query: String): String? {
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
