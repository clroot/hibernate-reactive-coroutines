package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.spring.boot.repository.query.PreparedQueryMethod
import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.domain.SliceImpl
import org.springframework.data.domain.Sort

/**
 * 페이징 관련 쿼리 실행을 담당하는 내부 헬퍼 클래스.
 *
 * Page, Slice 반환 타입의 쿼리 실행과 기본 findAll with Pageable/Sort를 처리합니다.
 * 스마트 스킵 최적화를 적용하여 불필요한 COUNT 쿼리를 생략합니다.
 *
 * @param T 엔티티 타입
 */
internal class PaginationOperations<T : Any>(
    private val entityClass: Class<T>,
    private val sessionProvider: TransactionalAwareSessionProvider,
    private val queryOps: QueryOperations<T>,
) {
    private val entityName: String = entityClass.simpleName

    // ============================================
    // 기본 findAll with Pageable/Sort
    // ============================================

    /**
     * 페이징을 적용하여 전체 조회합니다.
     *
     * Note: Hibernate Reactive는 동일 세션에서 병렬 쿼리를 지원하지 않으므로
     * 데이터 쿼리를 먼저 실행한 후 필요시 COUNT 쿼리를 실행합니다.
     */
    suspend fun findAllWithPageable(pageable: Pageable): Page<T> {
        val baseHql = "FROM $entityName e"
        val sortClause = queryOps.buildSortClause(pageable.sort)
        val hql = if (sortClause.isNotEmpty()) "$baseHql ORDER BY $sortClause" else baseHql
        val countHql = "SELECT COUNT(e) FROM $entityName e"

        val content = executeWithPaging(hql, emptyList(), pageable.pageSize, pageable.offset)
        val totalElements = calculateTotalElements(content, pageable, countHql, emptyList())

        return PageImpl(content, pageable, totalElements)
    }

    /**
     * 정렬을 적용하여 전체 조회합니다.
     */
    suspend fun findAllWithSort(sort: Sort): List<T> {
        val baseHql = "FROM $entityName e"
        val sortClause = queryOps.buildSortClause(sort)
        val hql = if (sortClause.isNotEmpty()) "$baseHql ORDER BY $sortClause" else baseHql

        return sessionProvider.read { session ->
            session.createQuery(hql, entityClass).resultList
        }
    }

    // ============================================
    // PartTree 기반 Page/Slice 쿼리
    // ============================================

    /**
     * Page 쿼리를 실행합니다.
     * 데이터 쿼리를 먼저 실행한 후 필요시 COUNT 쿼리를 실행하고, 스마트 스킵 최적화를 적용합니다.
     */
    suspend fun executePageQuery(
        prepared: PreparedQueryMethod,
        args: List<Any?>,
        pageable: Pageable,
    ): Page<T> {
        val hql = queryOps.applyDynamicSort(prepared.hql, pageable.sort)
        val countHql = prepared.countHql!!

        val content = executeWithPaging(hql, args, pageable.pageSize, pageable.offset)
        val totalElements = calculateTotalElements(content, pageable, countHql, args)

        return PageImpl(content, pageable, totalElements)
    }

    /**
     * Slice 쿼리를 실행합니다.
     * size+1개를 조회하여 다음 페이지 존재 여부를 확인합니다.
     */
    suspend fun executeSliceQuery(
        prepared: PreparedQueryMethod,
        args: List<Any?>,
        pageable: Pageable,
    ): Slice<T> {
        val hql = queryOps.applyDynamicSort(prepared.hql, pageable.sort)

        val content = executeWithPaging(hql, args, pageable.pageSize + 1, pageable.offset)

        val hasNext = content.size > pageable.pageSize
        val sliceContent = if (hasNext) content.dropLast(1) else content

        return SliceImpl(sliceContent, pageable, hasNext)
    }

    // ============================================
    // @Query 어노테이션 Page/Slice 쿼리
    // ============================================

    /**
     * @Query Page 쿼리를 실행합니다.
     */
    suspend fun executePageAnnotatedQuery(
        prepared: PreparedQueryMethod,
        args: List<Any?>,
        pageable: Pageable,
    ): Page<T> {
        val content = sessionProvider.read { session ->
            val query = if (prepared.isNativeQuery) {
                session.createNativeQuery(prepared.hql, entityClass)
            } else {
                session.createQuery(prepared.hql, entityClass)
            }

            queryOps.bindAnnotatedParameters(query, prepared, args)
            query.firstResult = pageable.offset.toInt()
            query.maxResults = pageable.pageSize
            query.resultList
        }

        val totalElements = if (shouldSkipCountQuery(content, pageable)) {
            pageable.offset + content.size
        } else {
            executeCountAnnotatedQuery(prepared, args)
        }

        return PageImpl(content, pageable, totalElements)
    }

    /**
     * @Query Slice 쿼리를 실행합니다.
     */
    suspend fun executeSliceAnnotatedQuery(
        prepared: PreparedQueryMethod,
        args: List<Any?>,
        pageable: Pageable,
    ): Slice<T> {
        val content = sessionProvider.read { session ->
            val query = if (prepared.isNativeQuery) {
                session.createNativeQuery(prepared.hql, entityClass)
            } else {
                session.createQuery(prepared.hql, entityClass)
            }

            queryOps.bindAnnotatedParameters(query, prepared, args)
            query.firstResult = pageable.offset.toInt()
            query.maxResults = pageable.pageSize + 1
            query.resultList
        }

        val hasNext = content.size > pageable.pageSize
        val sliceContent = if (hasNext) content.dropLast(1) else content

        return SliceImpl(sliceContent, pageable, hasNext)
    }

    /**
     * @Query COUNT 쿼리를 실행합니다.
     */
    private suspend fun executeCountAnnotatedQuery(
        prepared: PreparedQueryMethod,
        args: List<Any?>,
    ): Long {
        val countHql = prepared.countHql!!
        return sessionProvider.read { session ->
            val query = if (prepared.isNativeQuery) {
                session.createNativeQuery(countHql, Long::class.javaObjectType)
            } else {
                session.createQuery(countHql, Long::class.javaObjectType)
            }

            queryOps.bindAnnotatedParameters(query, prepared, args)
            query.singleResult
        } ?: 0L
    }

    // ============================================
    // 공통 헬퍼
    // ============================================

    /**
     * 페이징을 적용하여 쿼리를 실행합니다.
     */
    private suspend fun executeWithPaging(
        hql: String,
        args: List<Any?>,
        limit: Int,
        offset: Long,
    ): List<T> = sessionProvider.read { session ->
        val query = session.createQuery(hql, entityClass)
        args.forEachIndexed { index, arg ->
            query.setParameter("p$index", arg)
        }
        query.firstResult = offset.toInt()
        query.maxResults = limit
        query.resultList
    }

    /**
     * Page용 COUNT 쿼리를 실행합니다.
     */
    private suspend fun executeCountForPage(countHql: String, args: List<Any?>): Long =
        sessionProvider.read { session ->
            val query = session.createQuery(countHql, Long::class.javaObjectType)
            args.forEachIndexed { index, arg ->
                query.setParameter("p$index", arg)
            }
            query.singleResult
        } ?: 0L

    /**
     * 스마트 스킵 최적화를 적용하여 총 요소 수를 계산합니다.
     *
     * 첫 페이지이거나 결과가 있으면서 pageSize보다 적을 때만 최적화합니다.
     * (결과가 비어있는데 offset > 0이면 범위를 벗어난 것이므로 정확한 count 필요)
     */
    private suspend fun calculateTotalElements(
        content: List<T>,
        pageable: Pageable,
        countHql: String,
        args: List<Any?>,
    ): Long {
        return if (shouldSkipCountQuery(content, pageable)) {
            pageable.offset + content.size
        } else {
            executeCountForPage(countHql, args)
        }
    }

    /**
     * COUNT 쿼리를 생략해도 되는지 확인합니다.
     */
    private fun shouldSkipCountQuery(content: List<T>, pageable: Pageable): Boolean {
        return (content.isNotEmpty() || pageable.offset == 0L) && content.size < pageable.pageSize
    }
}
