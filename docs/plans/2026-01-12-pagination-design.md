# Pagination 지원 설계 문서

> GitHub Issue: #3

## 결정 사항

| 항목 | 결정 |
|------|------|
| 타입 시스템 | Spring Data Commons (`Pageable`, `Page`, `Slice`) |
| 반환 타입 | `Page<T>` + `Slice<T>` 둘 다 지원 |
| 지원 범위 | 커스텀 메서드 + `findAll(pageable)` + `findAll(sort)` |
| 정렬 우선순위 | Pageable > 메서드명 (메서드명은 기본값) |
| COUNT 최적화 | 순차 실행 + 스마트 스킵 |

## API 형태

```kotlin
interface UserRepository : CoroutineCrudRepository<User, Long> {
    // 기본 findAll 오버로드
    suspend fun findAll(pageable: Pageable): Page<User>
    suspend fun findAll(sort: Sort): List<User>

    // 커스텀 쿼리 + 페이징
    suspend fun findByStatus(status: Status, pageable: Pageable): Page<User>
    suspend fun findByStatus(status: Status, pageable: Pageable): Slice<User>

    // 메서드명 정렬 + 페이징 (Pageable에 Sort 없으면 CreatedAt DESC 적용)
    suspend fun findByStatusOrderByCreatedAtDesc(status: Status, pageable: Pageable): Page<User>
}
```

## 구현 컴포넌트

### 변경할 파일

1. **QueryReturnType** - PAGE, SLICE 타입 추가
2. **PartTreeHqlBuilder** - 페이징/정렬 HQL 생성
3. **HibernateReactiveRepositoryFactoryBean** - Pageable 파라미터 감지
4. **SimpleHibernateReactiveRepository** - 페이징 쿼리 실행 로직

### 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                    HibernateReactiveRepositoryFactoryBean   │
│  - 메서드 파싱 시 Pageable/Sort 파라미터 감지               │
│  - QueryReturnType에 PAGE, SLICE 추가                       │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                      PartTreeHqlBuilder                     │
│  - Sort 병합 로직 (Pageable 우선, 메서드명은 기본값)        │
│  - COUNT 쿼리 생성 (Page용)                                 │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                SimpleHibernateReactiveRepository            │
│  - executePageQuery(): 순차로 데이터 + COUNT 실행           │
│  - executeSliceQuery(): 데이터만 (size+1개 조회)            │
│  - findAll(pageable), findAll(sort) 메서드 추가             │
└─────────────────────────────────────────────────────────────┘
```

> **Note:** Hibernate Reactive는 동일 세션에서 병렬 쿼리를 지원하지 않아 순차 실행합니다.

## 페이징 쿼리 실행 로직

### Page<T> 반환

```kotlin
suspend fun executePageQuery(hql: String, countHql: String, pageable: Pageable, args: List<Any?>): Page<T> {
    // 순차 실행 (Hibernate Reactive는 동일 세션에서 병렬 쿼리 미지원)
    val content = executeWithPaging(hql, pageable.pageSize, pageable.offset, args)

    // 스마트 스킵: 결과가 size보다 적으면 COUNT 불필요
    val totalElements = if (content.size < pageable.pageSize) {
        pageable.offset + content.size
    } else {
        executeCount(countHql, args)
    }

    return PageImpl(content, pageable, totalElements)
}
```

### Slice<T> 반환

```kotlin
suspend fun executeSliceQuery(hql: String, pageable: Pageable, args: List<Any?>): Slice<T> {
    // size + 1개 조회해서 다음 페이지 존재 여부 확인
    val content = executeWithPaging(hql, pageable.pageSize + 1, pageable.offset, args)

    val hasNext = content.size > pageable.pageSize
    val sliceContent = if (hasNext) content.dropLast(1) else content

    SliceImpl(sliceContent, pageable, hasNext)
}
```

## HQL 생성

- HQL 자체에는 LIMIT/OFFSET 없음 (Hibernate Query API의 `setFirstResult()` / `setMaxResults()` 사용)
- Sort 병합: Pageable의 Sort가 있으면 사용, 없으면 메서드명 정렬 적용
- COUNT 쿼리는 기존 `buildCountQuery()` 로직 재사용

## 에러 처리

```kotlin
when {
    // Pageable이 중간에 있으면 에러
    pageable != lastParam -> throw IllegalStateException(
        "Pageable must be the last parameter"
    )

    // Page/Slice 반환인데 Pageable 없으면 에러
    returnType.isPage() && !hasPageable -> throw IllegalStateException(
        "Page/Slice return type requires Pageable parameter"
    )

    // 음수 페이지/사이즈
    pageable.pageNumber < 0 -> throw IllegalArgumentException(
        "Page index must not be negative"
    )
}
```

## 테스트 케이스

1. `findAll(pageable)` - 기본 페이징
2. `findAll(sort)` - 정렬만
3. `findByXxx(xxx, pageable)` - 커스텀 + 페이징
4. `Page` vs `Slice` 반환 타입 차이
5. 스마트 스킵 동작 (마지막 페이지)
6. 빈 결과 처리
7. 정렬 우선순위 (Pageable > 메서드명)
