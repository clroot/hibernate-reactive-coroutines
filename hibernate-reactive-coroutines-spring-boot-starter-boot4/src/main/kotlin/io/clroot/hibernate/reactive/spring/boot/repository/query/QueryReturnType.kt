package io.clroot.hibernate.reactive.spring.boot.repository.query

/**
 * 쿼리 메서드의 반환 타입.
 */
enum class QueryReturnType {
    /** 단일 엔티티 (nullable) */
    SINGLE,

    /** 엔티티 리스트 */
    LIST,

    /** Boolean (existsBy) */
    BOOLEAN,

    /** Long (countBy) */
    LONG,

    /** Unit/Void (deleteBy) */
    VOID,

    /** Page<T> (페이징 + 총 개수) */
    PAGE,

    /** Slice<T> (페이징, 총 개수 없음) */
    SLICE,

    /** Int (@Modifying 쿼리의 영향받은 행 수) */
    MODIFYING,
}
