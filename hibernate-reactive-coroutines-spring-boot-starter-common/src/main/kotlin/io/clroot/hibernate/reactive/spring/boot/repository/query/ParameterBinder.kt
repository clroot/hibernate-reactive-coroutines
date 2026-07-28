package io.clroot.hibernate.reactive.spring.boot.repository.query

import org.springframework.data.repository.query.parser.Part

/**
 * 쿼리 파라미터 바인더.
 *
 * Part.Type에 따라 파라미터 값을 변환합니다.
 * 예: CONTAINING → "%value%", STARTING_WITH → "value%"
 */
public sealed class ParameterBinder {

    /**
     * 파라미터 값을 바인딩에 적합한 형태로 변환합니다.
     */
    public abstract fun bind(value: Any?): Any?

    /**
     * 기본 바인더 - 값을 그대로 전달
     */
    public data object Direct : ParameterBinder() {
        override fun bind(value: Any?): Any? = value
    }

    /**
     * LIKE 패턴 바인더 - 값 양쪽에 % 추가
     */
    public data object Containing : ParameterBinder() {
        override fun bind(value: Any?): Any? = value?.let { "%${escapeLikeWildcards(it)}%" }
    }

    /**
     * StartingWith 패턴 바인더 - 값 뒤에 % 추가
     */
    public data object StartingWith : ParameterBinder() {
        override fun bind(value: Any?): Any? = value?.let { "${escapeLikeWildcards(it)}%" }
    }

    /**
     * EndingWith 패턴 바인더 - 값 앞에 % 추가
     */
    public data object EndingWith : ParameterBinder() {
        override fun bind(value: Any?): Any? = value?.let { "%${escapeLikeWildcards(it)}" }
    }

    public companion object {
        /**
         * LIKE 패턴에서 특별한 의미를 갖는 문자를 이스케이프합니다.
         *
         * 이스케이프하지 않으면 `findByNameContaining("%")` 같은 호출이 전체 행을 매칭하여
         * 의도한 필터를 우회합니다. [LIKE_ESCAPE_CHARACTER]와 짝을 이룹니다.
         */
        internal fun escapeLikeWildcards(value: Any): String =
            value.toString()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")

        /** 이스케이프된 LIKE 패턴에 사용할 HQL `ESCAPE` 절. */
        internal const val LIKE_ESCAPE_CLAUSE: String = " ESCAPE '\\'"

        /**
         * Part.Type에 맞는 ParameterBinder를 반환합니다.
         */
        public fun forType(type: Part.Type): ParameterBinder = when (type) {
            Part.Type.CONTAINING, Part.Type.NOT_CONTAINING -> Containing
            Part.Type.STARTING_WITH -> StartingWith
            Part.Type.ENDING_WITH -> EndingWith
            else -> Direct
        }
    }
}
