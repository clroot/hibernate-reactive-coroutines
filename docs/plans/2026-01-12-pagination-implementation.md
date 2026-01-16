# Pagination Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Spring Data Commons의 Pageable/Page/Slice를 활용한 페이징 기능 구현

**Architecture:** 메서드 파싱 시 Pageable 파라미터와 Page/Slice 반환 타입을 감지하여 QueryReturnType을 결정하고, 실행 시 순차적으로 데이터+COUNT 쿼리를 실행하거나 size+1 트릭으로 Slice를 반환

**Tech Stack:** Spring Data Commons (Pageable, Page, Slice, PageImpl, SliceImpl), Kotlin Coroutines

> **Note:** Hibernate Reactive는 동일 세션에서 병렬 쿼리를 지원하지 않으므로 데이터와 COUNT 쿼리는 순차적으로 실행됩니다.

---

## Task 1: QueryReturnType에 PAGE, SLICE 추가

**Files:**
- Modify: `hibernate-reactive-coroutines-spring-boot-starter/src/main/kotlin/io/clroot/hibernate/reactive/spring/boot/repository/query/PreparedQueryMethod.kt:28-43`

**Step 1: PAGE, SLICE enum 추가**

```kotlin
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
}
```

**Step 2: 컴파일 확인**

Run: `./gradlew :hibernate-reactive-coroutines-spring-boot-starter:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add -A && git commit -m "feat(#3): QueryReturnType에 PAGE, SLICE 추가"
```

---

## Task 2: PreparedQueryMethod에 countHql 필드 추가

**Files:**
- Modify: `hibernate-reactive-coroutines-spring-boot-starter/src/main/kotlin/io/clroot/hibernate/reactive/spring/boot/repository/query/PreparedQueryMethod.kt:17-23`

**Step 1: countHql 필드 추가**

```kotlin
data class PreparedQueryMethod(
    val method: Method,
    val partTree: PartTree,
    val hql: String,
    /** Page 반환 타입일 때 사용할 COUNT HQL (null이면 COUNT 불필요) */
    val countHql: String?,
    val parameterBinders: List<ParameterBinder>,
    val returnType: QueryReturnType,
)
```

**Step 2: 컴파일 확인**

Run: `./gradlew :hibernate-reactive-coroutines-spring-boot-starter:compileKotlin`
Expected: BUILD FAILED (기존 코드에서 countHql 전달 안 함)

**Step 3: HibernateReactiveRepositoryFactoryBean 임시 수정**

`parseMethod` 함수에서 `countHql = null` 추가:

```kotlin
return PreparedQueryMethod(
    method = method,
    partTree = partTree,
    hql = buildResult.hql,
    countHql = null,  // 추가
    parameterBinders = buildResult.parameterBinders,
    returnType = returnType,
)
```

**Step 4: 컴파일 확인**

Run: `./gradlew :hibernate-reactive-coroutines-spring-boot-starter:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add -A && git commit -m "feat(#3): PreparedQueryMethod에 countHql 필드 추가"
```

---

## Task 3: PartTreeHqlBuilder에 Sort 병합 및 COUNT 쿼리 생성 로직 추가

**Files:**
- Modify: `hibernate-reactive-coroutines-spring-boot-starter/src/main/kotlin/io/clroot/hibernate/reactive/spring/boot/repository/query/PartTreeHqlBuilder.kt`
- Test: `hibernate-reactive-coroutines-spring-boot-starter/src/test/kotlin/io/clroot/hibernate/reactive/spring/boot/repository/query/PartTreeHqlBuilderTest.kt`

**Step 1: 테스트 작성 - Sort 병합**

`PartTreeHqlBuilderTest.kt`에 추가:

```kotlin
describe("Sort 병합") {
    context("동적 Sort가 주어졌을 때") {
        it("동적 Sort를 우선 적용한다") {
            // given
            val partTree = PartTree("findAllByValueOrderByNameAsc", TestEntity::class.java)
            val builder = PartTreeHqlBuilder("TestEntity", partTree)
            val dynamicSort = Sort.by(Sort.Direction.DESC, "value")

            // when
            val result = builder.buildWithSort(dynamicSort)

            // then
            result.hql shouldContain "ORDER BY e.value DESC"
            result.hql shouldNotContain "ORDER BY e.name ASC"
        }
    }

    context("동적 Sort가 없을 때") {
        it("메서드명의 정렬을 적용한다") {
            // given
            val partTree = PartTree("findAllByValueOrderByNameAsc", TestEntity::class.java)
            val builder = PartTreeHqlBuilder("TestEntity", partTree)

            // when
            val result = builder.buildWithSort(null)

            // then
            result.hql shouldContain "ORDER BY e.name ASC"
        }
    }

    context("동적 Sort가 unsorted일 때") {
        it("메서드명의 정렬을 적용한다") {
            // given
            val partTree = PartTree("findAllByValueOrderByNameDesc", TestEntity::class.java)
            val builder = PartTreeHqlBuilder("TestEntity", partTree)

            // when
            val result = builder.buildWithSort(Sort.unsorted())

            // then
            result.hql shouldContain "ORDER BY e.name DESC"
        }
    }
}
```

**Step 2: 테스트 실행 - 실패 확인**

Run: `./gradlew :hibernate-reactive-coroutines-spring-boot-starter:test --tests "*.PartTreeHqlBuilderTest" -x processTestAot`
Expected: FAIL (buildWithSort 메서드 없음)

**Step 3: buildWithSort 메서드 구현**

`PartTreeHqlBuilder.kt`에 추가:

```kotlin
/**
 * 동적 Sort를 적용하여 HQL을 생성합니다.
 * 동적 Sort가 있으면 우선 적용하고, 없으면 메서드명의 정렬을 사용합니다.
 */
fun buildWithSort(dynamicSort: Sort?): HqlBuildResult {
    parameterIndex = 0
    parameterBinders.clear()

    val hql = when {
        partTree.isCountProjection -> buildCountQuery()
        partTree.isExistsProjection -> buildExistsQuery()
        partTree.isDelete -> buildDeleteQuery()
        else -> buildSelectQueryWithSort(dynamicSort)
    }

    return HqlBuildResult(hql, parameterBinders.toList())
}

private fun buildSelectQueryWithSort(dynamicSort: Sort?): String {
    val where = buildWhereClause()
    val effectiveSort = if (dynamicSort != null && dynamicSort.isSorted) {
        dynamicSort
    } else {
        partTree.sort
    }
    val orderBy = buildOrderByClause(effectiveSort)

    return buildString {
        append("FROM $entityName e")
        if (where.isNotEmpty()) {
            append(" WHERE $where")
        }
        if (orderBy.isNotEmpty()) {
            append(" ORDER BY $orderBy")
        }
    }
}

private fun buildOrderByClause(sort: Sort): String {
    if (sort.isUnsorted) return ""

    return sort.map { order ->
        val direction = if (order.isAscending) "ASC" else "DESC"
        "e.${order.property} $direction"
    }.joinToString(", ")
}
```

기존 `buildOrderByClause()` 수정 (파라미터 없는 버전은 partTree.sort 사용):

```kotlin
private fun buildOrderByClause(): String {
    return buildOrderByClause(partTree.sort)
}
```

**Step 4: 테스트 실행 - 통과 확인**

Run: `./gradlew :hibernate-reactive-coroutines-spring-boot-starter:test --tests "*.PartTreeHqlBuilderTest" -x processTestAot`
Expected: PASS

**Step 5: 테스트 작성 - COUNT 쿼리 생성 (public 메서드)**

`PartTreeHqlBuilderTest.kt`에 추가:

```kotlin
describe("buildCountHql") {
    it("SELECT COUNT HQL을 생성한다") {
        // given
        val partTree = PartTree("findAllByValue", TestEntity::class.java)
        val builder = PartTreeHqlBuilder("TestEntity", partTree)

        // when
        val countHql = builder.buildCountHql()

        // then
        countHql shouldBe "SELECT COUNT(e) FROM TestEntity e WHERE e.value = :p0"
    }

    it("조건이 없으면 전체 COUNT를 생성한다") {
        // given
        val partTree = PartTree("findAll", TestEntity::class.java)
        val builder = PartTreeHqlBuilder("TestEntity", partTree)

        // when
        val countHql = builder.buildCountHql()

        // then
        countHql shouldBe "SELECT COUNT(e) FROM TestEntity e"
    }
}
```

**Step 6: 테스트 실행 - 실패 확인**

Run: `./gradlew :hibernate-reactive-coroutines-spring-boot-starter:test --tests "*.PartTreeHqlBuilderTest" -x processTestAot`
Expected: FAIL (buildCountHql 메서드 없음)

**Step 7: buildCountHql 메서드 구현**

`PartTreeHqlBuilder.kt`에 추가:

```kotlin
/**
 * Page 반환 타입을 위한 COUNT HQL을 생성합니다.
 */
fun buildCountHql(): String {
    parameterIndex = 0
    parameterBinders.clear()

    val where = buildWhereClause()
    return buildString {
        append("SELECT COUNT(e) FROM $entityName e")
        if (where.isNotEmpty()) {
            append(" WHERE $where")
        }
    }
}
```

**Step 8: 테스트 실행 - 통과 확인**

Run: `./gradlew :hibernate-reactive-coroutines-spring-boot-starter:test --tests "*.PartTreeHqlBuilderTest" -x processTestAot`
Expected: PASS

**Step 9: Commit**

```bash
git add -A && git commit -m "feat(#3): PartTreeHqlBuilder에 Sort 병합 및 COUNT 쿼리 생성 추가"
```

---

## Task 4: HibernateReactiveRepositoryFactoryBean에서 Pageable/Page/Slice 감지

**Files:**
- Modify: `hibernate-reactive-coroutines-spring-boot-starter/src/main/kotlin/io/clroot/hibernate/reactive/spring/boot/repository/HibernateReactiveRepositoryFactoryBean.kt`
- Test: `hibernate-reactive-coroutines-spring-boot-starter/src/test/kotlin/io/clroot/hibernate/reactive/spring/boot/repository/HibernateReactiveRepositoryFactoryBeanTest.kt`

**Step 1: import 추가**

```kotlin
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.domain.Sort
```

**Step 2: Pageable 파라미터 감지 함수 추가**

```kotlin
/**
 * 메서드의 마지막 파라미터(Continuation 제외)가 Pageable인지 확인합니다.
 */
private fun hasPageableParameter(method: Method): Boolean {
    val params = method.parameterTypes
    // Continuation을 제외한 마지막 파라미터 확인
    val lastNonContinuationIndex = params.size - 2  // 마지막은 Continuation
    if (lastNonContinuationIndex < 0) return false
    return Pageable::class.java.isAssignableFrom(params[lastNonContinuationIndex])
}

/**
 * 메서드의 마지막 파라미터(Continuation 제외)가 Sort인지 확인합니다.
 */
private fun hasSortParameter(method: Method): Boolean {
    val params = method.parameterTypes
    val lastNonContinuationIndex = params.size - 2
    if (lastNonContinuationIndex < 0) return false
    return Sort::class.java.isAssignableFrom(params[lastNonContinuationIndex])
}
```

**Step 3: Page/Slice 반환 타입 감지 함수 추가**

```kotlin
/**
 * 메서드가 Page를 반환하는지 확인합니다.
 */
private fun isPageReturnType(method: Method): Boolean {
    val actualReturnType = extractActualReturnType(method) ?: return false
    return isAssignableToRawType(actualReturnType, Page::class.java)
}

/**
 * 메서드가 Slice를 반환하는지 확인합니다.
 */
private fun isSliceReturnType(method: Method): Boolean {
    val actualReturnType = extractActualReturnType(method) ?: return false
    // Slice이지만 Page가 아닌 경우 (Page extends Slice)
    return isAssignableToRawType(actualReturnType, Slice::class.java) &&
           !isAssignableToRawType(actualReturnType, Page::class.java)
}

/**
 * suspend 함수의 실제 반환 타입을 추출합니다.
 */
private fun extractActualReturnType(method: Method): Type? {
    val genericParams = method.genericParameterTypes
    if (genericParams.isEmpty()) return null

    val lastParam = genericParams.last()
    if (lastParam !is ParameterizedType) return null
    if (lastParam.rawType != Continuation::class.java) return null

    val typeArg = lastParam.actualTypeArguments.firstOrNull() ?: return null
    return unwrapWildcard(typeArg)
}

/**
 * 타입이 특정 클래스에 할당 가능한지 확인합니다.
 */
private fun isAssignableToRawType(type: Type, targetClass: Class<*>): Boolean {
    return when (type) {
        is ParameterizedType -> {
            val rawType = type.rawType as? Class<*>
            rawType != null && targetClass.isAssignableFrom(rawType)
        }
        is Class<*> -> targetClass.isAssignableFrom(type)
        else -> false
    }
}
```

**Step 4: determineReturnType 수정**

```kotlin
private fun determineReturnType(method: Method, partTree: PartTree): QueryReturnType {
    return when {
        partTree.isExistsProjection -> QueryReturnType.BOOLEAN
        partTree.isCountProjection -> QueryReturnType.LONG
        partTree.isDelete -> QueryReturnType.VOID
        isPageReturnType(method) -> QueryReturnType.PAGE
        isSliceReturnType(method) -> QueryReturnType.SLICE
        isListReturnType(method) -> QueryReturnType.LIST
        else -> QueryReturnType.SINGLE
    }
}
```

**Step 5: parseMethod 수정 - countHql 생성**

```kotlin
private fun parseMethod(method: Method, entityClass: Class<*>): PreparedQueryMethod {
    val partTree = PartTree(method.name, entityClass)
    val entityName = entityClass.simpleName

    val builder = PartTreeHqlBuilder(entityName, partTree)
    val returnType = determineReturnType(method, partTree)

    // Pageable이 있으면 동적 Sort 적용을 위해 buildWithSort 사용
    val hasPageable = hasPageableParameter(method)
    val hasSort = hasSortParameter(method)

    val buildResult = if (hasPageable || hasSort) {
        // Sort는 런타임에 적용되므로 null 전달 (메서드명 정렬만 적용)
        builder.buildWithSort(null)
    } else {
        builder.build()
    }

    // Page 반환 타입일 때만 countHql 생성
    val countHql = if (returnType == QueryReturnType.PAGE) {
        builder.buildCountHql()
    } else {
        null
    }

    return PreparedQueryMethod(
        method = method,
        partTree = partTree,
        hql = buildResult.hql,
        countHql = countHql,
        parameterBinders = buildResult.parameterBinders,
        returnType = returnType,
    )
}
```

**Step 6: 에러 검증 추가**

parseMethod 시작 부분에 검증 추가:

```kotlin
private fun parseMethod(method: Method, entityClass: Class<*>): PreparedQueryMethod {
    val hasPageable = hasPageableParameter(method)
    val returnType = determineReturnTypeEarly(method)

    // Page/Slice 반환인데 Pageable이 없으면 에러
    if ((returnType == QueryReturnType.PAGE || returnType == QueryReturnType.SLICE) && !hasPageable) {
        throw IllegalStateException(
            "Method '${method.name}' returns Page/Slice but has no Pageable parameter"
        )
    }

    // ... 나머지 로직
}

// 빠른 반환 타입 확인 (PartTree 파싱 전)
private fun determineReturnTypeEarly(method: Method): QueryReturnType? {
    return when {
        isPageReturnType(method) -> QueryReturnType.PAGE
        isSliceReturnType(method) -> QueryReturnType.SLICE
        else -> null
    }
}
```

**Step 7: 컴파일 및 기존 테스트 확인**

Run: `./gradlew :hibernate-reactive-coroutines-spring-boot-starter:test -x processTestAot`
Expected: PASS (기존 테스트 모두 통과)

**Step 8: Commit**

```bash
git add -A && git commit -m "feat(#3): HibernateReactiveRepositoryFactoryBean에서 Pageable/Page/Slice 감지"
```

---

## Task 5: SimpleHibernateReactiveRepository에 페이징 쿼리 실행 로직 추가

**Files:**
- Modify: `hibernate-reactive-coroutines-spring-boot-starter/src/main/kotlin/io/clroot/hibernate/reactive/spring/boot/repository/SimpleHibernateReactiveRepository.kt`

**Step 1: import 추가**

```kotlin
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.domain.SliceImpl
import org.springframework.data.domain.Sort
```

**Step 2: BASE_METHODS에 findAll(pageable), findAll(sort) 추가 고려**

기본 메서드로 처리하지 않고 커스텀 쿼리 메서드로 처리하도록 유지 (메서드 시그니처로 구분)

**Step 3: executeQueryMethod에 PAGE, SLICE 케이스 추가**

```kotlin
private suspend fun executeQueryMethod(
    prepared: PreparedQueryMethod,
    args: List<Any?>,
): Any? {
    // Pageable/Sort 파라미터 추출
    val (queryArgs, pageable, sort) = extractPagingParams(args)

    // 파라미터 바인딩
    val boundArgs = queryArgs.mapIndexed { index, arg ->
        prepared.parameterBinders.getOrNull(index)?.bind(arg) ?: arg
    }

    return when (prepared.returnType) {
        QueryReturnType.SINGLE -> executeSingleQuery(prepared.hql, boundArgs)
        QueryReturnType.LIST -> {
            if (sort != null) {
                executeListQueryWithSort(prepared, boundArgs, sort)
            } else {
                executeListQuery(prepared.hql, boundArgs)
            }
        }
        QueryReturnType.BOOLEAN -> executeExistsQuery(prepared.hql, boundArgs)
        QueryReturnType.LONG -> executeCountQuery(prepared.hql, boundArgs)
        QueryReturnType.VOID -> executeDeleteQuery(prepared.hql, boundArgs)
        QueryReturnType.PAGE -> executePageQuery(prepared, boundArgs, pageable!!)
        QueryReturnType.SLICE -> executeSliceQuery(prepared, boundArgs, pageable!!)
    }
}
```

**Step 4: extractPagingParams 함수 추가**

```kotlin
/**
 * 인자 목록에서 Pageable/Sort를 분리합니다.
 */
private fun extractPagingParams(args: List<Any?>): Triple<List<Any?>, Pageable?, Sort?> {
    if (args.isEmpty()) return Triple(args, null, null)

    val lastArg = args.last()
    return when (lastArg) {
        is Pageable -> Triple(args.dropLast(1), lastArg, lastArg.sort)
        is Sort -> Triple(args.dropLast(1), null, lastArg)
        else -> Triple(args, null, null)
    }
}
```

**Step 5: executePageQuery 구현**

```kotlin
/**
 * Page 쿼리를 실행합니다.
 * Hibernate Reactive의 제약으로 순차 실행하며, 스마트 스킵 최적화를 적용합니다.
 */
private suspend fun executePageQuery(
    prepared: PreparedQueryMethod,
    args: List<Any?>,
    pageable: Pageable,
): Page<T> {
    val hql = applyDynamicSort(prepared.hql, pageable.sort)
    val countHql = requireNotNull(prepared.countHql)

    // 순차 실행 (Hibernate Reactive는 동일 세션에서 병렬 쿼리 미지원)
    val content = executeWithPaging(hql, args, pageable.pageSize, pageable.offset)

    // 스마트 스킵: 결과가 pageSize보다 적으면 정확한 총 개수 계산 가능
    val totalElements = if (content.size < pageable.pageSize) {
        pageable.offset + content.size
    } else {
        executeCountForPage(countHql, args)
    }

    return PageImpl(content, pageable, totalElements)
}
```

**Step 6: executeSliceQuery 구현**

```kotlin
/**
 * Slice 쿼리를 실행합니다.
 * size+1개를 조회하여 다음 페이지 존재 여부를 확인합니다.
 */
private suspend fun executeSliceQuery(
    prepared: PreparedQueryMethod,
    args: List<Any?>,
    pageable: Pageable,
): Slice<T> {
    val hql = applyDynamicSort(prepared, pageable.sort)

    // size + 1개 조회
    val content = executeWithPaging(hql, args, pageable.pageSize + 1, pageable.offset)

    val hasNext = content.size > pageable.pageSize
    val sliceContent = if (hasNext) content.dropLast(1) else content

    return SliceImpl(sliceContent, pageable, hasNext)
}
```

**Step 7: 헬퍼 함수들 구현**

```kotlin
/**
 * 동적 Sort를 HQL에 적용합니다.
 */
private fun applyDynamicSort(prepared: PreparedQueryMethod, sort: Sort): String {
    if (sort.isUnsorted) return prepared.hql

    // 기존 ORDER BY 제거 후 새 정렬 추가
    val baseHql = prepared.hql.replace(Regex(" ORDER BY .+$"), "")
    val orderBy = sort.map { order ->
        val direction = if (order.isAscending) "ASC" else "DESC"
        "e.${order.property} $direction"
    }.joinToString(", ")

    return "$baseHql ORDER BY $orderBy"
}

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
 * Sort만 적용된 List 쿼리를 실행합니다.
 */
private suspend fun executeListQueryWithSort(
    prepared: PreparedQueryMethod,
    args: List<Any?>,
    sort: Sort,
): List<T> {
    val hql = applyDynamicSort(prepared, sort)
    return executeListQuery(hql, args)
}
```

**Step 8: 컴파일 확인**

Run: `./gradlew :hibernate-reactive-coroutines-spring-boot-starter:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 9: Commit**

```bash
git add -A && git commit -m "feat(#3): SimpleHibernateReactiveRepository에 페이징 쿼리 실행 로직 추가"
```

---

## Task 6: 기본 findAll(pageable), findAll(sort) 메서드 지원

**Files:**
- Modify: `hibernate-reactive-coroutines-spring-boot-starter/src/main/kotlin/io/clroot/hibernate/reactive/spring/boot/repository/SimpleHibernateReactiveRepository.kt`

**Step 1: invokeSuspend에 findAll 오버로드 처리 추가**

```kotlin
private fun invokeSuspend(
    methodName: String,
    args: List<Any?>,
    continuation: Continuation<Any?>,
): Any {
    val future: CompletableFuture<Any?> = scope.future(continuation.context) {
        @Suppress("UNCHECKED_CAST")
        when (methodName) {
            // ... 기존 케이스들 ...
            "findAll" -> {
                when {
                    args.isEmpty() -> findAll().toList()  // Flow를 List로 변환하지 않음, 별도 처리
                    args[0] is Pageable -> findAllWithPageable(args[0] as Pageable)
                    args[0] is Sort -> findAllWithSort(args[0] as Sort)
                    else -> throw IllegalArgumentException("Invalid argument for findAll")
                }
            }
            // ... 나머지 ...
        }
    }
    // ...
}
```

**Step 2: findAllWithPageable, findAllWithSort 구현**

```kotlin
/**
 * 페이징을 적용하여 전체 조회합니다.
 * Hibernate Reactive의 제약으로 순차 실행합니다.
 */
suspend fun findAllWithPageable(pageable: Pageable): Page<T> {
    val baseHql = "FROM $entityName e"
    val hql = if (pageable.sort.isSorted) {
        "$baseHql ORDER BY ${buildSortClause(pageable.sort)}"
    } else {
        baseHql
    }
    val countHql = "SELECT COUNT(e) FROM $entityName e"

    // 순차 실행 (Hibernate Reactive는 동일 세션에서 병렬 쿼리 미지원)
    val content = executeWithPaging(hql, emptyList(), pageable.pageSize, pageable.offset)

    // 스마트 스킵
    val totalElements = if (content.size < pageable.pageSize) {
        pageable.offset + content.size
    } else {
        executeCountForPage(countHql, emptyList())
    }

    return PageImpl(content, pageable, totalElements)
}

/**
 * 정렬을 적용하여 전체 조회합니다.
 */
suspend fun findAllWithSort(sort: Sort): List<T> {
    val baseHql = "FROM $entityName e"
    val hql = if (sort.isSorted) {
        val orderBy = sort.map { order ->
            val direction = if (order.isAscending) "ASC" else "DESC"
            "e.${order.property} $direction"
        }.joinToString(", ")
        "$baseHql ORDER BY $orderBy"
    } else {
        baseHql
    }

    return sessionProvider.read { session ->
        session.createQuery(hql, entityClass).resultList
    }
}
```

**Step 3: isFlowMethod 수정 (findAll만 있을 때는 Flow)**

```kotlin
private fun isFlowMethod(methodName: String): Boolean {
    return methodName in setOf("findAllById", "saveAll")
    // findAll은 인자에 따라 다르게 처리하므로 제외
}
```

**Step 4: invoke에서 findAll 분기 추가**

```kotlin
override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any {
    // ... 기존 코드 ...

    // Flow 반환 메서드 처리 - findAll은 인자 없을 때만
    if (method.name == "findAll" && (args == null || args.size == 1)) {
        // args.size == 1은 Continuation만 있는 경우 (suspend fun findAll(): Flow<T>)
        // 실제로는 suspend가 아닌 일반 함수로 Flow 반환
        return findAll()
    }

    if (isFlowMethod(method.name)) {
        return invokeFlowMethod(method.name, args?.toList() ?: emptyList())
    }

    // ... 나머지 코드 ...
}
```

**Step 5: 컴파일 확인**

Run: `./gradlew :hibernate-reactive-coroutines-spring-boot-starter:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add -A && git commit -m "feat(#3): 기본 findAll(pageable), findAll(sort) 메서드 지원"
```

---

## Task 7: TestEntityRepository에 페이징 메서드 추가

**Files:**
- Modify: `hibernate-reactive-coroutines-spring-boot-starter/src/testFixtures/kotlin/io/clroot/hibernate/reactive/test/TestEntityRepository.kt`

**Step 1: import 추가 및 메서드 추가**

```kotlin
package io.clroot.hibernate.reactive.test

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.domain.Sort
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface TestEntityRepository : CoroutineCrudRepository<TestEntity, Long> {

    // 기존 메서드들...

    // 페이징 - Page 반환
    suspend fun findAllByValue(value: Int, pageable: Pageable): Page<TestEntity>

    // 페이징 - Slice 반환
    suspend fun findAllByValueGreaterThan(value: Int, pageable: Pageable): Slice<TestEntity>

    // 메서드명 정렬 + 페이징
    suspend fun findAllByValueOrderByNameDesc(value: Int, pageable: Pageable): Page<TestEntity>

    // 기본 findAll 오버로드
    suspend fun findAll(pageable: Pageable): Page<TestEntity>
    suspend fun findAll(sort: Sort): List<TestEntity>
}
```

**Step 2: 컴파일 확인**

Run: `./gradlew :hibernate-reactive-coroutines-spring-boot-starter:compileTestFixturesKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add -A && git commit -m "feat(#3): TestEntityRepository에 페이징 메서드 추가"
```

---

## Task 8: 페이징 통합 테스트 작성

**Files:**
- Create: `hibernate-reactive-coroutines-spring-boot-starter/src/test/kotlin/io/clroot/hibernate/reactive/test/PaginationIntegrationTest.kt`

**Step 1: 테스트 파일 생성**

```kotlin
package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort

@SpringBootTest(classes = [TestApplication::class])
class PaginationIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("페이징") {

            beforeEach {
                // 테스트 데이터 준비: 10개 엔티티
                tx.transactional {
                    repeat(10) { i ->
                        testEntityRepository.save(
                            TestEntity(name = "page_test_${i.toString().padStart(2, '0')}", value = 100)
                        )
                    }
                }
            }

            context("findAll(pageable) - 기본 페이징") {
                it("첫 번째 페이지를 조회한다") {
                    val pageable = PageRequest.of(0, 3)

                    val page = tx.readOnly {
                        testEntityRepository.findAll(pageable)
                    }

                    page.content shouldHaveSize 3
                    page.totalElements shouldBe 10
                    page.totalPages shouldBe 4
                    page.number shouldBe 0
                    page.hasNext() shouldBe true
                    page.hasPrevious() shouldBe false
                }

                it("마지막 페이지를 조회한다") {
                    val pageable = PageRequest.of(3, 3)

                    val page = tx.readOnly {
                        testEntityRepository.findAll(pageable)
                    }

                    page.content shouldHaveSize 1
                    page.totalElements shouldBe 10
                    page.number shouldBe 3
                    page.hasNext() shouldBe false
                    page.hasPrevious() shouldBe true
                }

                it("정렬을 적용한다") {
                    val pageable = PageRequest.of(0, 3, Sort.by(Sort.Direction.DESC, "name"))

                    val page = tx.readOnly {
                        testEntityRepository.findAll(pageable)
                    }

                    page.content.map { it.name } shouldContainExactly listOf(
                        "page_test_09", "page_test_08", "page_test_07"
                    )
                }
            }

            context("findAll(sort) - 정렬만") {
                it("정렬을 적용하여 전체를 조회한다") {
                    val sort = Sort.by(Sort.Direction.DESC, "name")

                    val list = tx.readOnly {
                        testEntityRepository.findAll(sort)
                    }

                    list shouldHaveSize 10
                    list.first().name shouldBe "page_test_09"
                    list.last().name shouldBe "page_test_00"
                }
            }

            context("findAllByValue(value, pageable) - 커스텀 쿼리 + Page") {
                it("조건과 페이징을 함께 적용한다") {
                    val pageable = PageRequest.of(0, 5)

                    val page = tx.readOnly {
                        testEntityRepository.findAllByValue(100, pageable)
                    }

                    page.content shouldHaveSize 5
                    page.totalElements shouldBe 10
                    page.content.all { it.value == 100 } shouldBe true
                }
            }

            context("findAllByValueGreaterThan(value, pageable) - Slice") {
                it("COUNT 없이 다음 페이지 여부만 확인한다") {
                    // 추가 데이터: value = 200
                    tx.transactional {
                        repeat(5) { i ->
                            testEntityRepository.save(
                                TestEntity(name = "slice_test_$i", value = 200)
                            )
                        }
                    }

                    val pageable = PageRequest.of(0, 3)

                    val slice = tx.readOnly {
                        testEntityRepository.findAllByValueGreaterThan(150, pageable)
                    }

                    slice.content shouldHaveSize 3
                    slice.hasNext() shouldBe true
                    // Slice는 totalElements가 없음
                }

                it("마지막 페이지에서 hasNext는 false") {
                    tx.transactional {
                        repeat(2) { i ->
                            testEntityRepository.save(
                                TestEntity(name = "slice_last_$i", value = 300)
                            )
                        }
                    }

                    val pageable = PageRequest.of(0, 5)

                    val slice = tx.readOnly {
                        testEntityRepository.findAllByValueGreaterThan(250, pageable)
                    }

                    slice.content shouldHaveSize 2
                    slice.hasNext() shouldBe false
                }
            }

            context("정렬 우선순위") {
                it("Pageable의 Sort가 메서드명 정렬보다 우선한다") {
                    // findAllByValueOrderByNameDesc + Pageable(sort=ASC)
                    val pageable = PageRequest.of(0, 3, Sort.by(Sort.Direction.ASC, "name"))

                    val page = tx.readOnly {
                        testEntityRepository.findAllByValueOrderByNameDesc(100, pageable)
                    }

                    // Pageable의 ASC가 적용되어야 함
                    page.content.map { it.name } shouldContainExactly listOf(
                        "page_test_00", "page_test_01", "page_test_02"
                    )
                }

                it("Pageable에 Sort가 없으면 메서드명 정렬을 적용한다") {
                    val pageable = PageRequest.of(0, 3)

                    val page = tx.readOnly {
                        testEntityRepository.findAllByValueOrderByNameDesc(100, pageable)
                    }

                    // 메서드명의 DESC가 적용되어야 함
                    page.content.map { it.name } shouldContainExactly listOf(
                        "page_test_09", "page_test_08", "page_test_07"
                    )
                }
            }

            context("스마트 스킵 최적화") {
                it("마지막 페이지에서는 COUNT 쿼리를 스킵한다") {
                    // 데이터 3개만 있는 상황
                    tx.transactional {
                        repeat(3) { i ->
                            testEntityRepository.save(
                                TestEntity(name = "smart_skip_$i", value = 999)
                            )
                        }
                    }

                    val pageable = PageRequest.of(0, 10)

                    val page = tx.readOnly {
                        testEntityRepository.findAllByValue(999, pageable)
                    }

                    // content.size(3) < pageSize(10) 이므로
                    // totalElements = offset(0) + content.size(3) = 3
                    page.content shouldHaveSize 3
                    page.totalElements shouldBe 3
                }
            }

            context("빈 결과") {
                it("일치하는 데이터가 없으면 빈 Page를 반환한다") {
                    val pageable = PageRequest.of(0, 10)

                    val page = tx.readOnly {
                        testEntityRepository.findAllByValue(999999, pageable)
                    }

                    page.content shouldHaveSize 0
                    page.totalElements shouldBe 0
                    page.hasNext() shouldBe false
                }
            }
        }
    }
}
```

**Step 2: 테스트 실행**

Run: `./gradlew :hibernate-reactive-coroutines-spring-boot-starter:test --tests "*.PaginationIntegrationTest" -x processTestAot`
Expected: PASS

**Step 3: Commit**

```bash
git add -A && git commit -m "test(#3): 페이징 통합 테스트 작성"
```

---

## Task 9: 전체 테스트 실행 및 최종 확인

**Step 1: 전체 테스트 실행**

Run: `./gradlew test -x processTestAot`
Expected: PASS

**Step 2: 빌드 확인**

Run: `./gradlew build -x processTestAot`
Expected: BUILD SUCCESSFUL

**Step 3: 최종 커밋 (필요시)**

```bash
git add -A && git commit -m "feat(#3): Pagination 기능 구현 완료"
```

---

## Summary

| Task | Description | Files |
|------|-------------|-------|
| 1 | QueryReturnType에 PAGE, SLICE 추가 | PreparedQueryMethod.kt |
| 2 | PreparedQueryMethod에 countHql 필드 추가 | PreparedQueryMethod.kt |
| 3 | PartTreeHqlBuilder Sort 병합/COUNT 쿼리 | PartTreeHqlBuilder.kt |
| 4 | FactoryBean에서 Pageable/Page/Slice 감지 | HibernateReactiveRepositoryFactoryBean.kt |
| 5 | Repository에 페이징 쿼리 실행 로직 | SimpleHibernateReactiveRepository.kt |
| 6 | 기본 findAll(pageable/sort) 지원 | SimpleHibernateReactiveRepository.kt |
| 7 | TestEntityRepository에 페이징 메서드 추가 | TestEntityRepository.kt |
| 8 | 페이징 통합 테스트 | PaginationIntegrationTest.kt |
| 9 | 전체 테스트 및 빌드 확인 | - |
