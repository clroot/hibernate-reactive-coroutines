package io.clroot.hibernate.reactive.spring.boot.repository.query

import org.springframework.data.repository.query.parser.Part

/**
 * Transforms query parameter values for a derived query part.
 *
 * For example, `CONTAINING` produces `"%value%"` and `STARTING_WITH` produces `"value%"`.
 */
internal sealed class ParameterBinder {

    public abstract fun bind(value: Any?): Any?

    public data object Direct : ParameterBinder() {
        override fun bind(value: Any?): Any? = value
    }

    internal data object InCollection : ParameterBinder() {
        override fun bind(value: Any?): Any = requireCollection(value, "IN")
    }

    internal data object NotInCollection : ParameterBinder() {
        override fun bind(value: Any?): Any = requireCollection(value, "NOT IN")
    }

    /** Adds wildcards on both sides of an escaped value. */
    public data object Containing : ParameterBinder() {
        override fun bind(value: Any?): Any? = value?.let { "%${escapeLikeWildcards(it)}%" }
    }

    /** Adds a trailing wildcard to an escaped value. */
    public data object StartingWith : ParameterBinder() {
        override fun bind(value: Any?): Any? = value?.let { "${escapeLikeWildcards(it)}%" }
    }

    /** Adds a leading wildcard to an escaped value. */
    public data object EndingWith : ParameterBinder() {
        override fun bind(value: Any?): Any? = value?.let { "%${escapeLikeWildcards(it)}" }
    }

    public companion object {
        /**
         * Escapes characters with special meaning in a LIKE pattern.
         *
         * Without escaping, `findByNameContaining("%")` matches every row instead of the literal
         * value, bypassing the intended filter. Pair this with [LIKE_ESCAPE_CLAUSE].
         */
        internal fun escapeLikeWildcards(value: Any): String =
            value.toString()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")

        private fun requireCollection(value: Any?, operator: String): Any {
            requireNotNull(value) { "$operator collection parameter must not be null" }
            require(value is Iterable<*> || value.javaClass.isArray) {
                "$operator parameter must be a collection or array"
            }
            return value
        }

        /** HQL `ESCAPE` clause paired with escaped LIKE patterns. */
        internal const val LIKE_ESCAPE_CLAUSE: String = " ESCAPE '\\'"

        public fun forType(type: Part.Type): ParameterBinder = when (type) {
            Part.Type.CONTAINING, Part.Type.NOT_CONTAINING -> Containing
            Part.Type.STARTING_WITH -> StartingWith
            Part.Type.ENDING_WITH -> EndingWith
            Part.Type.IN -> InCollection
            Part.Type.NOT_IN -> NotInCollection
            else -> Direct
        }
    }
}
