package io.clroot.hibernate.reactive.repository.query

import java.util.Locale

public enum class QueryStatementType {
    SELECT,
    MODIFYING,
    UNKNOWN,
}

public object CountQueryDeriver {
    private val selectClauseRegex = Regex(
        "^SELECT\\s+(?:(DISTINCT)\\s+)?(.+?)\\s+FROM\\s+",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val simpleAliasRegex = Regex("[\\p{L}_$][\\p{L}\\p{N}_$]*")
    private val rootAliasRegex = Regex(
        "^\\s*(?:[\\p{L}_$][\\p{L}\\p{N}_$]*|`(?:``|\\\\.|[^`])*`)" +
                "(?:\\.(?:[\\p{L}_$][\\p{L}\\p{N}_$]*|`(?:``|\\\\.|[^`])*`))*" +
                "\\s+(?:AS\\s+)?([\\p{L}_$][\\p{L}\\p{N}_$]*)(?=\\s|$)",
        RegexOption.IGNORE_CASE,
    )
    private val rootFromRegex = Regex(
        "^\\s*((?:[\\p{L}_$][\\p{L}\\p{N}_$]*|`(?:``|\\\\.|[^`])*`)" +
                "(?:\\.(?:[\\p{L}_$][\\p{L}\\p{N}_$]*|`(?:``|\\\\.|[^`])*`))*)" +
                "(?:\\s+(?:(AS)\\s+)?([\\p{L}_$][\\p{L}\\p{N}_$]*))?",
        RegexOption.IGNORE_CASE,
    )
    private val separatorRegex = Regex(
        "(?:\\s|/\\*.*?\\*/)*",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val setOperations = setOf("UNION", "INTERSECT", "EXCEPT")
    private val setModifiers = setOf("ALL", "DISTINCT")
    private val paginationClauses = setOf("LIMIT", "OFFSET", "FETCH")
    private val fetchDirections = setOf("FIRST", "NEXT")
    private val pathFunctions = setOf("ELEMENT", "VALUE", "KEY", "INDEX", "TREAT")
    private val clauseStarters = setOf(
        "WHERE", "GROUP", "HAVING", "ORDER", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER",
        "FULL", "CROSS", "UNION", "INTERSECT", "EXCEPT", "LIMIT", "OFFSET", "FETCH", "SELECT",
    )

    private data class QueryToken(
        val value: String,
        val start: Int,
        val end: Int,
        val qualified: Boolean,
    )

    private data class ScanResult(
        val tokens: List<QueryToken>,
        val allTokens: List<QueryToken>,
        val bindParameters: List<Int>,
        val topLevelCommas: List<Int>,
        val quotedQualifiedSegments: List<Int>,
    )

    private data class RootAlias(
        val value: String?,
        val end: Int,
        val fromEnd: Int,
    )

    private enum class ScanState {
        DEFAULT,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        BACKTICK,
        BLOCK_COMMENT,
    }

    public fun derive(query: String): String {
        val normalized = query.trim()
        val scan = scan(normalized)
        val tokens = scan.tokens
        val rootAlias = findRootAlias(normalized)
        val structuralTokens = rootAlias
            ?.let { alias -> tokens.filter { it.start >= alias.end } }
            ?: tokens
        if (structuralTokens.findSequence(normalized, "GROUP", "BY") != null) {
            unsupported("GROUP BY")
        }
        structuralTokens.findJoin(normalized)?.let(::unsupported)
        structuralTokens.findSetOperation(normalized)?.let { unsupported(it.value) }
        structuralTokens.firstOrNull { !it.qualified && it.value == "HAVING" }
            ?.let { unsupported("HAVING") }
        structuralTokens.findPaginationClause(normalized)?.let { unsupported(it.value) }

        val orderBy = structuralTokens.findSequence(normalized, "ORDER", "BY")
        val fromEnd = rootAlias?.fromEnd
            ?: tokens.firstOrNull { !it.qualified && it.value == "FROM" }?.end
            ?: unsupported("missing FROM")
        val countBodyEnd = orderBy?.start ?: normalized.length
        if (scan.topLevelCommas.any { it > fromEnd && it < countBodyEnd }) {
            unsupported("multiple query roots")
        }
        if (orderBy != null && scan.bindParameters.any { it >= orderBy.start }) {
            unsupported("parameterized ORDER BY")
        }
        if (rootAlias != null && scan.hasUnsafeRootPath(normalized, rootAlias, countBodyEnd)) {
            unsupported("implicit join")
        }
        if (orderBy != null) {
            val byToken = structuralTokens[structuralTokens.indexOf(orderBy) + 1]
            if (!normalized.substring(byToken.end).isSafeOrderClause(rootAlias?.value)) {
                unsupported("complex or implicit join in ORDER BY")
            }
        }
        val queryWithoutOrder = orderBy
            ?.let { normalized.substring(0, it.start).trimEnd() }
            ?: normalized

        return when (tokens.firstOrNull()?.value) {
            "FROM" -> {
                if (structuralTokens.any { !it.qualified && it.value == "SELECT" }) {
                    unsupported("trailing SELECT")
                }
                "SELECT COUNT(*) $queryWithoutOrder"
            }

            "SELECT" ->
                deriveSelectCount(queryWithoutOrder)

            else -> throw IllegalStateException(
                "Automatic count query derivation requires a SELECT or FROM query",
            )
        }
    }

    private fun deriveSelectCount(query: String): String {
        val selectClause = selectClauseRegex.find(query)
            ?: unsupported("complex SELECT")
        val isDistinct = selectClause.groupValues[1].isNotEmpty()
        val selection = selectClause.groupValues[2].trim()
        if (!simpleAliasRegex.matches(selection)) {
            unsupported("complex SELECT")
        }
        val fromClause = query.substring(selectClause.range.last + 1)
        val rootAlias = rootAliasRegex.find(fromClause)?.groupValues?.get(1)
            ?: unsupported("complex FROM")
        if (!selection.equals(rootAlias, ignoreCase = true)) {
            unsupported("non-entity projection")
        }

        val countExpression = if (isDistinct) "DISTINCT $selection" else "*"
        return "SELECT COUNT($countExpression) FROM $fromClause"
    }

    private fun findRootAlias(query: String): RootAlias? {
        val tokens = scan(query).tokens
        val firstToken = tokens.firstOrNull() ?: return null
        val fromEnd = when (firstToken.value) {
            "SELECT" -> selectClauseRegex.find(query)?.range?.last?.plus(1) ?: return null
            "FROM" -> firstToken.end
            else -> return null
        }
        val root = rootFromRegex.find(query.substring(fromEnd)) ?: return null
        val entity = root.groups[1] ?: return null
        val explicitAs = root.groups[2] != null
        val aliasCandidate = root.groups[3]
        val selection = if (firstToken.value == "SELECT") {
            selectClauseRegex.find(query)?.groupValues?.get(2)?.trim()
        } else {
            null
        }
        val aliasEnd = aliasCandidate?.range?.last?.plus(fromEnd)?.plus(1)
        val nextToken = aliasEnd?.let { end ->
            tokens.firstOrNull { it.start >= end }
        }
        val alias = aliasCandidate?.takeIf {
            explicitAs || it.value.uppercase(Locale.ROOT) !in clauseStarters ||
                    it.value.equals(selection, ignoreCase = true) ||
                    it.value.equals("JOIN", ignoreCase = true) &&
                    (nextToken == null || nextToken.value in aliasFollowingClauses)
        }
        val rootEnd = alias?.range?.last?.plus(1) ?: entity.range.last + 1
        return RootAlias(
            value = alias?.value,
            end = fromEnd + rootEnd,
            fromEnd = fromEnd,
        )
    }

    /** Detects a top-level `ORDER BY`, ignoring keywords inside literals and comments. */
    public fun hasOrderBy(query: String): Boolean =
        scan(query).tokens.findSequence(query, "ORDER", "BY") != null

    /**
     * Finds the executable top-level statement while ignoring leading block comments and CTE bodies.
     */
    public fun statementType(query: String): QueryStatementType {
        val tokens = scan(query.trim()).tokens.filterNot { it.qualified }
        val first = tokens.firstOrNull() ?: return QueryStatementType.UNKNOWN
        val statement = if (first.value == "WITH") {
            tokens.drop(1).firstOrNull { it.value in STATEMENT_TOKENS }
        } else {
            first
        }
        return when (statement?.value) {
            "SELECT", "FROM" -> QueryStatementType.SELECT
            "UPDATE", "DELETE", "INSERT" -> QueryStatementType.MODIFYING
            else -> QueryStatementType.UNKNOWN
        }
    }

    private fun unsupported(feature: String): Nothing {
        throw IllegalStateException(
            "Automatic count query derivation does not support $feature; declare countQuery explicitly",
        )
    }

    private val STATEMENT_TOKENS = setOf("SELECT", "FROM", "UPDATE", "DELETE", "INSERT")

    private val aliasFollowingClauses = setOf(
        "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "JOIN",
    )

    private fun List<QueryToken>.findSequence(query: String, vararg values: String): QueryToken? {
        if (size < values.size) return null
        return indices
            .take(size - values.size + 1)
            .firstOrNull { index ->
                values.indices.all { offset ->
                    val token = this[index + offset]
                    val previous = getOrNull(index + offset - 1)
                    !token.qualified &&
                            token.value == values[offset] &&
                            (previous == null || offset == 0 || query.hasOnlySeparators(previous.end, token.start))
                }
            }
            ?.let(::get)
    }

    private fun List<QueryToken>.findJoin(query: String): String? {
        return indices.firstNotNullOfOrNull { index ->
            val join = this[index]
            val next = getOrNull(index + 1)
            if (join.qualified || join.value != "JOIN") return@firstNotNullOfOrNull null

            val nextTokenStartsTarget = next != null && query.hasOnlySeparators(join.end, next.start)
            val nextCharacter = query.nextCodeCharacter(join.end)
            if (!nextTokenStartsTarget && nextCharacter !in setOf('`', '(')) {
                return@firstNotNullOfOrNull null
            }

            if (next?.value == "FETCH" && nextTokenStartsTarget) "JOIN FETCH" else "JOIN"
        }
    }

    private fun List<QueryToken>.findPaginationClause(query: String): QueryToken? {
        return firstOrNull { token ->
            if (token.qualified || token.value !in paginationClauses) return@firstOrNull false
            val nextCharacter = query.nextCodeCharacter(token.end)
            when (token.value) {
                "FETCH" -> {
                    val index = indexOf(token)
                    val next = getOrNull(index + 1)
                    next != null && next.value in fetchDirections &&
                            query.hasOnlySeparators(token.end, next.start)
                }

                else -> nextCharacter?.isDigit() == true || nextCharacter == ':' || nextCharacter == '?'
            }
        }
    }

    private fun List<QueryToken>.findSetOperation(query: String): QueryToken? {
        for (index in indices) {
            val operation = this[index]
            if (operation.qualified || operation.value !in setOperations) continue
            if (query.nextCodeCharacter(operation.end) == '(') return operation

            var nextIndex = index + 1
            val modifier = getOrNull(nextIndex)
            var previous = operation
            if (modifier != null && modifier.value in setModifiers &&
                query.hasOnlySeparators(operation.end, modifier.start)
            ) {
                if (query.nextCodeCharacter(modifier.end) == '(') return operation
                previous = modifier
                nextIndex++
            }
            val next = getOrNull(nextIndex)
            val operandStartsQuery = next?.value in setOf("SELECT", "FROM", "WHERE", "ORDER")
            if (next != null && !next.qualified && operandStartsQuery &&
                query.hasOnlySeparators(previous.end, next.start)
            ) {
                return operation
            }
        }
        return null
    }

    private fun scan(query: String): ScanResult {
        val tokens = mutableListOf<QueryToken>()
        val allTokens = mutableListOf<QueryToken>()
        val bindParameters = mutableListOf<Int>()
        val topLevelCommas = mutableListOf<Int>()
        val quotedQualifiedSegments = mutableListOf<Int>()
        var state = ScanState.DEFAULT
        var depth = 0
        var index = 0

        while (index < query.length) {
            val current = query[index]
            val next = query.getOrNull(index + 1)

            when (state) {
                ScanState.SINGLE_QUOTE -> {
                    if (current == '\\' && next != null) {
                        index += 2
                        continue
                    }
                    if (current == '\'' && next == '\'') {
                        index += 2
                        continue
                    }
                    if (current == '\'') state = ScanState.DEFAULT
                }

                ScanState.DOUBLE_QUOTE -> {
                    if (current == '\\' && next != null) {
                        index += 2
                        continue
                    }
                    if (current == '"' && next == '"') {
                        index += 2
                        continue
                    }
                    if (current == '"') state = ScanState.DEFAULT
                }

                ScanState.BACKTICK -> {
                    if (current == '\\' && next != null) {
                        index += 2
                        continue
                    }
                    if (current == '`' && next == '`') {
                        index += 2
                        continue
                    }
                    if (current == '`') state = ScanState.DEFAULT
                }

                ScanState.BLOCK_COMMENT -> {
                    if (current == '/' && next == '*') malformed()
                    if (current == '*' && next == '/') {
                        state = ScanState.DEFAULT
                        index += 2
                        continue
                    }
                }

                ScanState.DEFAULT -> when {
                    current == '\'' -> state = ScanState.SINGLE_QUOTE
                    current == '"' -> state = ScanState.DOUBLE_QUOTE
                    current == '`' -> {
                        if (query.previousSignificant(index) == '.') {
                            quotedQualifiedSegments += index
                        }
                        state = ScanState.BACKTICK
                    }
                    current == '*' && next == '/' -> malformed()
                    current == '$' && query.getOrNull(index - 1)?.isIdentifierPart() != true &&
                            query.dollarDelimiterAt(index) != null -> malformed()
                    current == '-' && next == '-' -> {
                        malformed()
                    }

                    current == '/' && next == '*' -> {
                        state = ScanState.BLOCK_COMMENT
                        index += 2
                        continue
                    }

                    current == '(' -> depth++
                    current == ')' -> {
                        if (depth == 0) malformed()
                        depth--
                    }

                    (current == ':' && next?.isIdentifierStart() == true) ||
                            (current == '?' && next?.isDigit() == true) -> bindParameters += index

                    depth == 0 && current == ',' -> topLevelCommas += index

                    current.isIdentifierStart() -> {
                        val start = index
                        index++
                        while (index < query.length && query[index].isIdentifierPart()) {
                            index++
                        }
                        val previousSignificant = query.previousSignificant(start)
                        val nextSignificant = query.nextSignificant(index)
                        val token = QueryToken(
                            value = query.substring(start, index).uppercase(Locale.ROOT),
                            start = start,
                            end = index,
                            qualified = previousSignificant == '.' || nextSignificant == '.',
                        )
                        allTokens += token
                        if (depth == 0) tokens += token
                        continue
                    }
                }
            }

            index++
        }

        if (depth != 0 || state != ScanState.DEFAULT) {
            malformed()
        }
        return ScanResult(tokens, allTokens, bindParameters, topLevelCommas, quotedQualifiedSegments)
    }

    private fun ScanResult.hasUnsafeRootPath(
        query: String,
        rootAlias: RootAlias,
        end: Int,
    ): Boolean {
        if (quotedQualifiedSegments.any { it > rootAlias.end && it < end }) return true
        val relevantTokens = allTokens.filter { it.start >= rootAlias.end && it.start < end }
        if (relevantTokens.any { token ->
                !token.qualified && token.value in pathFunctions &&
                        query.nextCodeCharacter(token.end) == '('
            }
        ) {
            return true
        }

        val rootAliasValue = rootAlias.value
        if (rootAliasValue == null) {
            return relevantTokens.indices.any { index ->
                val property = relevantTokens[index]
                if (query.nextCodeCharacter(property.end) == '[') return@any true
                val nestedProperty = relevantTokens.getOrNull(index + 1) ?: return@any false
                query.hasOnlyPathSeparator(property.end, nestedProperty.start)
            }
        }

        for (index in relevantTokens.indices) {
            val alias = relevantTokens[index]
            if (!alias.value.equals(rootAliasValue, ignoreCase = true)) continue
            val property = relevantTokens.getOrNull(index + 1) ?: continue
            if (!query.hasOnlyPathSeparator(alias.end, property.start)) continue
            if (query.nextCodeCharacter(property.end) == '[') return true
            val nestedProperty = relevantTokens.getOrNull(index + 2) ?: continue
            if (query.hasOnlyPathSeparator(property.end, nestedProperty.start)) return true
        }
        return false
    }

    private fun String.isSafeOrderClause(rootAlias: String?): Boolean {
        val identifier = "[\\p{L}_$][\\p{L}\\p{N}_$]*"
        val property = rootAlias
            ?.let { "(?:(?:${Regex.escape(it)})\\s*\\.\\s*)?$identifier" }
            ?: identifier
        val item = "$property(?:\\s+(?:ASC|DESC))?(?:\\s+NULLS\\s+(?:FIRST|LAST))?"
        return Regex(
            "^\\s*$item(?:\\s*,\\s*$item)*\\s*$",
            RegexOption.IGNORE_CASE,
        ).matches(this)
    }

    private fun Char.isIdentifierStart(): Boolean = isLetter() || this == '_' || this == '$'

    private fun Char.isIdentifierPart(): Boolean = isLetterOrDigit() || this == '_' || this == '$'

    private fun String.dollarDelimiterAt(start: Int): String? {
        var cursor = start + 1
        if (getOrNull(cursor) == '$') return substring(start, cursor + 1)
        if (getOrNull(cursor)?.let { it.isLetter() || it == '_' } != true) return null
        cursor++
        while (getOrNull(cursor)?.let { it.isLetterOrDigit() || it == '_' } == true) cursor++
        if (getOrNull(cursor) != '$') return null
        return substring(start, cursor + 1)
    }

    private fun String.previousSignificant(index: Int): Char? {
        var cursor = index - 1
        while (cursor >= 0 && this[cursor].isWhitespace()) cursor--
        return getOrNull(cursor)
    }

    private fun String.nextSignificant(index: Int): Char? {
        var cursor = index
        while (cursor < length && this[cursor].isWhitespace()) cursor++
        return getOrNull(cursor)
    }

    private fun String.hasOnlySeparators(start: Int, end: Int): Boolean {
        return separatorRegex.matches(substring(start, end))
    }

    private fun String.hasOnlyPathSeparator(start: Int, end: Int): Boolean {
        val fragment = substring(start, end)
        val dot = fragment.indexOf('.')
        return dot >= 0 && fragment.indexOf('.', dot + 1) < 0 &&
                separatorRegex.matches(fragment.substring(0, dot)) &&
                separatorRegex.matches(fragment.substring(dot + 1))
    }

    private fun String.nextCodeCharacter(index: Int): Char? {
        var cursor = index
        while (cursor < length) {
            when {
                this[cursor].isWhitespace() -> cursor++
                startsWith("/*", cursor) -> {
                    val end = indexOf("*/", cursor + 2)
                    if (end < 0) return null
                    cursor = end + 2
                }

                else -> return this[cursor]
            }
        }
        return null
    }

    private fun malformed(): Nothing {
        throw IllegalStateException(
            "Automatic count query derivation cannot parse the query; declare countQuery explicitly",
        )
    }
}
