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
 * Repository for testing PartTree query derivation and custom queries.
 *
 * Includes method-name query derivation and explicit query variants.
 */
interface TestEntityRepository : CoroutineCrudRepository<TestEntity, Long> {

    suspend fun findByName(name: String): TestEntity?

    suspend fun findAllByValue(value: Int): List<TestEntity>

    suspend fun findAllByNameIn(names: List<String>): List<TestEntity>

    suspend fun findAllByNameNotIn(names: List<String>): List<TestEntity>

    suspend fun existsByName(name: String): Boolean

    suspend fun countByValue(value: Int): Long

    suspend fun deleteByName(name: String)

    suspend fun deleteAllByValue(value: Int): Long

    suspend fun findTop2ByOrderByValueDesc(): List<TestEntity>

    suspend fun findFirstByOrderByValueDesc(): TestEntity?

    suspend fun findAllByNameContaining(name: String): List<TestEntity>

    suspend fun findByNameAndValue(name: String, value: Int): TestEntity?

    suspend fun findAllByValueGreaterThan(value: Int): List<TestEntity>

    suspend fun findAllByValueOrderByNameDesc(value: Int): List<TestEntity>

    suspend fun findAllByValue(value: Int, pageable: Pageable): Page<TestEntity>

    suspend fun findAllByValueGreaterThan(value: Int, pageable: Pageable): Slice<TestEntity>

    suspend fun findAllByValueOrderByNameDesc(value: Int, pageable: Pageable): Page<TestEntity>

    suspend fun findAll(pageable: Pageable): Page<TestEntity>
    suspend fun findAll(sort: Sort): List<TestEntity>

    // Explicit named-parameter binding.
    @Query("SELECT e FROM TestEntity e WHERE e.value = :value")
    suspend fun findByValueWithQuery(@Param("value") value: Int): List<TestEntity>

    // Named parameters inferred from Kotlin parameter names.
    @Query("SELECT e FROM TestEntity e WHERE e.name = :name AND e.value = :value")
    suspend fun findByNameAndValueWithQuery(name: String, value: Int): TestEntity?

    // Positional parameter binding.
    @Query("SELECT e FROM TestEntity e WHERE e.value > ?1 AND e.value < ?2")
    suspend fun findByValueBetweenWithQuery(min: Int, max: Int): List<TestEntity>

    @Modifying
    @Query("UPDATE TestEntity e SET e.value = :newValue WHERE e.value = :oldValue")
    suspend fun updateValue(@Param("oldValue") oldValue: Int, @Param("newValue") newValue: Int): Int

    @Modifying
    @Query("UPDATE TestEntity e SET e.value = :newValue WHERE e.value = :oldValue")
    suspend fun updateValueWithoutCount(@Param("oldValue") oldValue: Int, @Param("newValue") newValue: Int)

    @Modifying(clearAutomatically = true)
    @Query("UPDATE TestEntity e SET e.name = :name WHERE e.id = :id")
    suspend fun updateNameAndClear(@Param("id") id: Long, @Param("name") name: String): Int

    @Modifying
    @Query("DELETE FROM TestEntity e WHERE e.value = :value")
    suspend fun deleteByValueWithQuery(@Param("value") value: Int): Int

    @Query("SELECT e FROM TestEntity e WHERE e.value = :value")
    suspend fun findByValueWithQueryPageable(@Param("value") value: Int, pageable: Pageable): Page<TestEntity>

    @Query("SELECT e FROM TestEntity e WHERE e.value > :minValue")
    suspend fun findByValueGreaterThanWithQuerySlice(@Param("minValue") minValue: Int, pageable: Pageable): Slice<TestEntity>

    // Explicit count query for pagination.
    @Query(
        value = "SELECT e FROM TestEntity e WHERE e.value = :value ORDER BY e.name",
        countQuery = "SELECT COUNT(e) FROM TestEntity e WHERE e.value = :value",
    )
    suspend fun findByValueWithExplicitCount(@Param("value") value: Int, pageable: Pageable): Page<TestEntity>

    // Dynamic sort appended to the query.
    @Query("SELECT e FROM TestEntity e WHERE e.value > :minValue")
    suspend fun findByValueGreaterThanWithQuery(
        @Param("minValue") minValue: Int,
        sort: Sort,
    ): List<TestEntity>

    // Dynamic sort with an existing `ORDER BY` clause.
    @Query("SELECT e FROM TestEntity e WHERE e.value > :minValue ORDER BY e.value DESC")
    suspend fun findOrderedByValueGreaterThanWithQuery(
        @Param("minValue") minValue: Int,
        sort: Sort,
    ): List<TestEntity>

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
