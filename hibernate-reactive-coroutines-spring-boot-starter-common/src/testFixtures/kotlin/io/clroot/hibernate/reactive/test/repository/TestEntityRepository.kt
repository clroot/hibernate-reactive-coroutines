package io.clroot.hibernate.reactive.test.repository

import io.clroot.hibernate.reactive.repository.query.Modifying
import io.clroot.hibernate.reactive.repository.query.Param
import io.clroot.hibernate.reactive.repository.query.Query
import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.projection.TestEntitySummary
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.domain.Sort
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 테스트용 Repository 인터페이스.
 *
 * 커스텀 쿼리 메서드를 통해 PartTree 기반 쿼리 자동 생성을 테스트합니다.
 */
interface TestEntityRepository : CoroutineCrudRepository<TestEntity, Long> {

    // 단일 조회
    suspend fun findByName(name: String): TestEntity?

    // 리스트 조회
    suspend fun findAllByValue(value: Int): List<TestEntity>

    suspend fun findAllByNameIn(names: List<String>): List<TestEntity>

    suspend fun findAllByNameNotIn(names: List<String>): List<TestEntity>

    // 존재 여부
    suspend fun existsByName(name: String): Boolean

    // 카운트
    suspend fun countByValue(value: Int): Long

    // 삭제
    suspend fun deleteByName(name: String)

    // 삭제 건수 반환
    suspend fun deleteAllByValue(value: Int): Long

    // 개수 제한 (Top/First)
    suspend fun findTop2ByOrderByValueDesc(): List<TestEntity>

    suspend fun findFirstByOrderByValueDesc(): TestEntity?

    // LIKE 검색 (Containing)
    suspend fun findAllByNameContaining(name: String): List<TestEntity>

    // 복합 조건 (And)
    suspend fun findByNameAndValue(name: String, value: Int): TestEntity?

    // 비교 연산자
    suspend fun findAllByValueGreaterThan(value: Int): List<TestEntity>

    // 정렬
    suspend fun findAllByValueOrderByNameDesc(value: Int): List<TestEntity>

    // === 페이징 메서드 ===

    // 페이징 - Page 반환
    suspend fun findAllByValue(value: Int, pageable: Pageable): Page<TestEntity>

    // 페이징 - Slice 반환
    suspend fun findAllByValueGreaterThan(value: Int, pageable: Pageable): Slice<TestEntity>

    // 메서드명 정렬 + 페이징
    suspend fun findAllByValueOrderByNameDesc(value: Int, pageable: Pageable): Page<TestEntity>

    // 기본 findAll 오버로드
    suspend fun findAll(pageable: Pageable): Page<TestEntity>
    suspend fun findAll(sort: Sort): List<TestEntity>

    // === @Query 메서드 ===

    // Named Parameter + @Param
    @Query("SELECT e FROM TestEntity e WHERE e.value = :value")
    suspend fun findByValueWithQuery(@Param("value") value: Int): List<TestEntity>

    // Named Parameter (파라미터 이름 자동 추출)
    @Query("SELECT e FROM TestEntity e WHERE e.name = :name AND e.value = :value")
    suspend fun findByNameAndValueWithQuery(name: String, value: Int): TestEntity?

    // Positional Parameter
    @Query("SELECT e FROM TestEntity e WHERE e.value > ?1 AND e.value < ?2")
    suspend fun findByValueBetweenWithQuery(min: Int, max: Int): List<TestEntity>

    // @Modifying UPDATE
    @Modifying
    @Query("UPDATE TestEntity e SET e.value = :newValue WHERE e.value = :oldValue")
    suspend fun updateValue(@Param("oldValue") oldValue: Int, @Param("newValue") newValue: Int): Int

    @Modifying
    @Query("UPDATE TestEntity e SET e.value = :newValue WHERE e.value = :oldValue")
    suspend fun updateValueWithoutCount(@Param("oldValue") oldValue: Int, @Param("newValue") newValue: Int)

    @Modifying(clearAutomatically = true)
    @Query("UPDATE TestEntity e SET e.name = :name WHERE e.id = :id")
    suspend fun updateNameAndClear(@Param("id") id: Long, @Param("name") name: String): Int

    // @Modifying DELETE
    @Modifying
    @Query("DELETE FROM TestEntity e WHERE e.value = :value")
    suspend fun deleteByValueWithQuery(@Param("value") value: Int): Int

    // @Query + Page
    @Query("SELECT e FROM TestEntity e WHERE e.value = :value")
    suspend fun findByValueWithQueryPageable(@Param("value") value: Int, pageable: Pageable): Page<TestEntity>

    // @Query + Slice
    @Query("SELECT e FROM TestEntity e WHERE e.value > :minValue")
    suspend fun findByValueGreaterThanWithQuerySlice(@Param("minValue") minValue: Int, pageable: Pageable): Slice<TestEntity>

    // @Query + 명시적 countQuery
    @Query(
        value = "SELECT e FROM TestEntity e WHERE e.value = :value ORDER BY e.name",
        countQuery = "SELECT COUNT(e) FROM TestEntity e WHERE e.value = :value",
    )
    suspend fun findByValueWithExplicitCount(@Param("value") value: Int, pageable: Pageable): Page<TestEntity>

    // @Query + 동적 Sort
    @Query("SELECT e FROM TestEntity e WHERE e.value > :minValue")
    suspend fun findByValueGreaterThanWithQuery(
        @Param("minValue") minValue: Int,
        sort: Sort,
    ): List<TestEntity>

    // @Query에 ORDER BY가 이미 있는 경우
    @Query("SELECT e FROM TestEntity e WHERE e.value > :minValue ORDER BY e.value DESC")
    suspend fun findOrderedByValueGreaterThanWithQuery(
        @Param("minValue") minValue: Int,
        sort: Sort,
    ): List<TestEntity>

    // === @Query 프로젝션 ===

    @Query("SELECT COUNT(e) FROM TestEntity e WHERE e.value >= :minValue")
    suspend fun countProjectedByMinValue(@Param("minValue") minValue: Int): Long

    @Query("SELECT e.name FROM TestEntity e WHERE e.value >= :minValue ORDER BY e.name")
    suspend fun findProjectedNamesByMinValue(@Param("minValue") minValue: Int): List<String>

    @Query(
        "SELECT new io.clroot.hibernate.reactive.test.projection.TestEntitySummary(e.name, e.value) " +
                "FROM TestEntity e WHERE e.name = :name",
    )
    suspend fun findProjectedSummaryByName(@Param("name") name: String): TestEntitySummary?

    @Query(
        "SELECT new io.clroot.hibernate.reactive.test.projection.TestEntitySummary(e.name, e.value) " +
                "FROM TestEntity e WHERE e.value >= :minValue ORDER BY e.name",
    )
    suspend fun findProjectedSummariesByMinValue(
        @Param("minValue") minValue: Int,
    ): List<TestEntitySummary>

    @Query(
        value = "SELECT new io.clroot.hibernate.reactive.test.projection.TestEntitySummary(e.name, e.value) " +
                "FROM TestEntity e WHERE e.value = :value ORDER BY e.name",
        countQuery = "SELECT COUNT(e) FROM TestEntity e WHERE e.value = :value",
    )
    suspend fun findProjectedSummariesByValue(
        @Param("value") value: Int,
        pageable: Pageable,
    ): Page<TestEntitySummary>

    @Query(
        "SELECT new io.clroot.hibernate.reactive.test.projection.TestEntitySummary(e.name, e.value) " +
                "FROM TestEntity e WHERE e.value = :value ORDER BY e.name",
    )
    suspend fun findProjectedSummarySliceByValue(
        @Param("value") value: Int,
        pageable: Pageable,
    ): Slice<TestEntitySummary>
}
