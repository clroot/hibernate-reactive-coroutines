package io.clroot.hibernate.reactive.spring.boot.repository.query

import org.springframework.data.repository.query.parser.Part

/**
 * 쿼리 파라미터 바인더.
 *
 * Part.Type에 따라 파라미터 값을 변환합니다.
 * 예: CONTAINING → "%value%", STARTING_WITH → "value%"
 */
sealed class ParameterBinder {

    /**
     * 파라미터 값을 바인딩에 적합한 형태로 변환합니다.
     */
    abstract fun bind(value: Any?): Any?

    /**
     * 기본 바인더 - 값을 그대로 전달
     */
    data object Direct : ParameterBinder() {
        override fun bind(value: Any?): Any? = value
    }

    /**
     * LIKE 패턴 바인더 - 값 양쪽에 % 추가
     */
    data object Containing : ParameterBinder() {
        override fun bind(value: Any?): Any? = value?.let { "%$it%" }
    }

    /**
     * StartingWith 패턴 바인더 - 값 뒤에 % 추가
     */
    data object StartingWith : ParameterBinder() {
        override fun bind(value: Any?): Any? = value?.let { "$it%" }
    }

    /**
     * EndingWith 패턴 바인더 - 값 앞에 % 추가
     */
    data object EndingWith : ParameterBinder() {
        override fun bind(value: Any?): Any? = value?.let { "%$it" }
    }

    companion object {
        /**
         * Part.Type에 맞는 ParameterBinder를 반환합니다.
         */
        fun forType(type: Part.Type): ParameterBinder = when (type) {
            Part.Type.CONTAINING, Part.Type.NOT_CONTAINING -> Containing
            Part.Type.STARTING_WITH -> StartingWith
            Part.Type.ENDING_WITH -> EndingWith
            else -> Direct
        }
    }
}
