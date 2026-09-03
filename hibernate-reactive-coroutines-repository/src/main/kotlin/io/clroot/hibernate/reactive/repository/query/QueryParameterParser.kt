package io.clroot.hibernate.reactive.repository.query

public data class QueryParameters(
    public val style: QueryParameterStyle,
    public val names: List<String> = emptyList(),
    public val positions: List<Int> = emptyList(),
)

public object QueryParameterParser {
    private enum class State {
        DEFAULT,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        BACKTICK,
        BLOCK_COMMENT,
    }

    public fun parse(query: String): QueryParameters {
        val names = linkedSetOf<String>()
        val positions = linkedSetOf<Int>()
        var state = State.DEFAULT
        var index = 0

        while (index < query.length) {
            val current = query[index]
            val next = query.getOrNull(index + 1)
            val openingDollarDelimiter = if (state == State.DEFAULT && current == '$' &&
                query.getOrNull(index - 1)?.isIdentifierPart() != true
            ) {
                query.dollarDelimiterAt(index)
            } else {
                null
            }

            when (state) {
                State.SINGLE_QUOTE -> {
                    if (current == '\\' && next != null) {
                        index += 2
                        continue
                    }
                    if (current == '\'' && next == '\'') {
                        index += 2
                        continue
                    }
                    if (current == '\'') state = State.DEFAULT
                }

                State.DOUBLE_QUOTE -> {
                    if (current == '\\' && next != null) {
                        index += 2
                        continue
                    }
                    if (current == '"' && next == '"') {
                        index += 2
                        continue
                    }
                    if (current == '"') state = State.DEFAULT
                }

                State.BACKTICK -> {
                    if (current == '\\' && next != null) {
                        index += 2
                        continue
                    }
                    if (current == '`' && next == '`') {
                        index += 2
                        continue
                    }
                    if (current == '`') state = State.DEFAULT
                }

                State.BLOCK_COMMENT -> {
                    if (current == '/' && next == '*') {
                        unsupported("nested block comments")
                    }
                    if (current == '*' && next == '/') {
                        state = State.DEFAULT
                        index += 2
                        continue
                    }
                }

                State.DEFAULT -> when {
                    current == '\'' -> state = State.SINGLE_QUOTE
                    current == '"' -> state = State.DOUBLE_QUOTE
                    current == '`' -> state = State.BACKTICK
                    current == '*' && next == '/' -> malformed()
                    openingDollarDelimiter != null -> unsupported("PostgreSQL dollar-quoted literals")
                    current == '-' && next == '-' -> unsupported("line comments")

                    current == '/' && next == '*' -> {
                        state = State.BLOCK_COMMENT
                        index += 2
                        continue
                    }

                    current == ':' && query.getOrNull(index - 1) != ':' &&
                            next?.isIdentifierStart() == true -> {
                        val start = index + 1
                        index = start + 1
                        while (index < query.length && query[index].isIdentifierPart()) index++
                        names += query.substring(start, index)
                        continue
                    }

                    current == '?' && next?.isDigit() == true -> {
                        val start = index + 1
                        index = start + 1
                        while (index < query.length && query[index].isDigit()) index++
                        val position = query.substring(start, index).toIntOrNull()
                        if (position == null || position < 1) malformed()
                        positions += position
                        continue
                    }

                    current == '?' -> unsupported("unlabeled positional parameters")
                }
            }

            index++
        }

        if (state != State.DEFAULT) malformed()
        if (names.isNotEmpty() && positions.isNotEmpty()) {
            throw IllegalStateException("Query mixes named (:name) and positional (?1) parameters")
        }
        if (positions.isNotEmpty() && positions.toSet() != (1..positions.max()).toSet()) {
            throw IllegalStateException(
                "Positional query parameters must start at ?1 and be contiguous",
            )
        }

        return when {
            names.isNotEmpty() -> QueryParameters(QueryParameterStyle.NAMED, names = names.toList())
            positions.isNotEmpty() -> QueryParameters(QueryParameterStyle.POSITIONAL, positions = positions.toList())
            else -> QueryParameters(QueryParameterStyle.NONE)
        }
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

    private fun malformed(): Nothing {
        throw IllegalStateException("Cannot parse query parameters")
    }

    private fun unsupported(syntax: String): Nothing {
        throw IllegalStateException("Query parameter parsing does not support $syntax")
    }
}
