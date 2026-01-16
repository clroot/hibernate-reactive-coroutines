package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.spring.boot.repository.query.Modifying
import io.clroot.hibernate.reactive.spring.boot.repository.query.Param
import io.clroot.hibernate.reactive.spring.boot.repository.query.ParameterStyle
import io.clroot.hibernate.reactive.spring.boot.repository.query.PartTreeHqlBuilder
import io.clroot.hibernate.reactive.spring.boot.repository.query.PreparedQueryMethod
import io.clroot.hibernate.reactive.spring.boot.repository.query.Query
import io.clroot.hibernate.reactive.spring.boot.repository.query.QueryConstants.ORDER_BY_REGEX
import io.clroot.hibernate.reactive.spring.boot.repository.query.QueryReturnType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.domain.Sort
import org.springframework.data.repository.query.parser.PartTree
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import kotlin.coroutines.Continuation
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.kotlinFunction

/**
 * Repository 인터페이스의 쿼리 메서드를 파싱하여 [PreparedQueryMethod]를 생성하는 파서.
 *
 * @Query 어노테이션 메서드와 PartTree 기반 메서드 모두 처리합니다.
 *
 * @param entityClass 엔티티 클래스
 */
internal class QueryMethodParser(
    private val entityClass: Class<*>,
) {
    private val entityName: String = entityClass.simpleName

    companion object {
        /** CoroutineCrudRepository의 기본 메서드 이름들 */
        private val BASE_METHODS = setOf(
            "save", "saveAll",
            "findById", "findAll", "findAllById",
            "existsById", "count",
            "deleteById", "delete", "deleteAllById", "deleteAll",
        )

        /** SELECT ~ FROM 패턴 (COUNT 쿼리 변환용) */
        private val SELECT_FROM_REGEX = Regex(
            "SELECT\\s+.*?\\s+FROM",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    }

    // ============================================
    // 메서드 파싱 진입점
    // ============================================

    /**
     * 메서드를 파싱하여 PreparedQueryMethod를 생성합니다.
     */
    fun parse(method: Method): PreparedQueryMethod {
        val queryAnnotation = method.getAnnotation(Query::class.java)

        return if (queryAnnotation != null) {
            parseAnnotatedQueryMethod(method, queryAnnotation)
        } else {
            parsePartTreeMethod(method)
        }
    }

    /**
     * 커스텀 쿼리 메서드인지 확인합니다.
     */
    fun isCustomQueryMethod(method: Method): Boolean {
        // 기본 메서드는 제외
        if (method.name in BASE_METHODS) return false

        // Object 클래스의 메서드는 제외
        if (method.declaringClass == Any::class.java) return false

        // Default 메서드는 제외
        if (method.isDefault) return false

        // suspend 함수인지 확인 (마지막 파라미터가 Continuation)
        val params = method.parameterTypes
        return params.isNotEmpty() && Continuation::class.java.isAssignableFrom(params.last())
    }

    /**
     * 메서드의 고유 키를 생성합니다.
     * 오버로딩된 메서드를 구분하기 위해 메서드명과 파라미터 개수를 조합합니다.
     */
    fun createMethodKey(method: Method): String {
        val paramCount = method.parameterTypes.size - 1  // Continuation 제외
        return "${method.name}#$paramCount"
    }

    // ============================================
    // @Query 어노테이션 메서드 파싱
    // ============================================

    private fun parseAnnotatedQueryMethod(
        method: Method,
        queryAnnotation: Query,
    ): PreparedQueryMethod {
        validateQueryAnnotation(method, queryAnnotation)

        val hasPageable = hasPageableParameter(method)
        val isModifying = method.isAnnotationPresent(Modifying::class.java)

        val returnType = determineAnnotatedReturnType(method, isModifying)

        // Page/Slice 반환인데 Pageable이 없으면 에러
        if ((returnType == QueryReturnType.PAGE || returnType == QueryReturnType.SLICE) && !hasPageable) {
            throw IllegalStateException(
                "Method '${method.name}' returns Page/Slice but has no Pageable parameter",
            )
        }

        val query = queryAnnotation.value
        val parameterStyle = detectParameterStyle(query)
        val parameterNames = if (parameterStyle == ParameterStyle.NAMED) {
            extractParameterNames(method)
        } else {
            emptyList()
        }

        val countHql = generateCountHqlIfNeeded(returnType, queryAnnotation, query)

        return PreparedQueryMethod(
            method = method,
            partTree = null,
            hql = query,
            countHql = countHql,
            parameterBinders = emptyList(),
            returnType = returnType,
            isAnnotatedQuery = true,
            isNativeQuery = queryAnnotation.nativeQuery,
            isModifying = isModifying,
            parameterStyle = parameterStyle,
            parameterNames = parameterNames,
        )
    }

    private fun determineAnnotatedReturnType(method: Method, isModifying: Boolean): QueryReturnType {
        return when {
            isModifying -> QueryReturnType.MODIFYING
            isPageReturnType(method) -> QueryReturnType.PAGE
            isSliceReturnType(method) -> QueryReturnType.SLICE
            isListReturnType(method) -> QueryReturnType.LIST
            else -> QueryReturnType.SINGLE
        }
    }

    private fun generateCountHqlIfNeeded(
        returnType: QueryReturnType,
        queryAnnotation: Query,
        query: String,
    ): String? {
        if (returnType != QueryReturnType.PAGE) return null

        return when {
            queryAnnotation.countQuery.isNotEmpty() -> queryAnnotation.countQuery
            queryAnnotation.nativeQuery -> throw IllegalStateException(
                "Native query with Page return type requires explicit countQuery",
            )

            else -> generateCountQuery(query)
        }
    }

    private fun validateQueryAnnotation(method: Method, queryAnnotation: Query) {
        val query = queryAnnotation.value
        val isModifying = method.isAnnotationPresent(Modifying::class.java)
        val trimmedQuery = query.trim()

        // @Modifying인데 SELECT 쿼리면 에러
        if (isModifying && trimmedQuery.startsWith("SELECT", ignoreCase = true)) {
            throw IllegalStateException(
                "@Modifying method '${method.name}' cannot have SELECT query",
            )
        }

        // SELECT 쿼리가 아닌데 @Modifying가 없으면 에러
        val isSelectOrFrom = trimmedQuery.startsWith("SELECT", ignoreCase = true) ||
                trimmedQuery.startsWith("FROM", ignoreCase = true)
        if (!isModifying && !isSelectOrFrom) {
            throw IllegalStateException(
                "Method '${method.name}' has UPDATE/DELETE query but missing @Modifying annotation",
            )
        }
    }

    // ============================================
    // PartTree 기반 메서드 파싱
    // ============================================

    private fun parsePartTreeMethod(method: Method): PreparedQueryMethod {
        val hasPageable = hasPageableParameter(method)
        val hasSort = hasSortParameter(method)

        val partTree = PartTree(method.name, entityClass)
        val returnType = determinePartTreeReturnType(method, partTree)

        if ((returnType == QueryReturnType.PAGE || returnType == QueryReturnType.SLICE) && !hasPageable) {
            throw IllegalStateException(
                "Method '${method.name}' returns Page/Slice but has no Pageable parameter",
            )
        }

        val builder = PartTreeHqlBuilder(entityName, partTree)
        val buildResult = if (hasPageable || hasSort) {
            builder.buildWithSort(null)
        } else {
            builder.build()
        }

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
            isAnnotatedQuery = false,
            isNativeQuery = false,
            isModifying = false,
            parameterStyle = ParameterStyle.NONE,
            parameterNames = emptyList(),
        )
    }

    private fun determinePartTreeReturnType(method: Method, partTree: PartTree): QueryReturnType {
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

    // ============================================
    // 파라미터 분석
    // ============================================

    private fun detectParameterStyle(query: String): ParameterStyle {
        val hasNamed = query.contains(Regex(":\\w+"))
        val hasPositional = query.contains(Regex("\\?\\d+"))

        return when {
            hasNamed && hasPositional -> throw IllegalStateException(
                "Query mixes named (:name) and positional (?1) parameters",
            )

            hasNamed -> ParameterStyle.NAMED
            hasPositional -> ParameterStyle.POSITIONAL
            else -> ParameterStyle.NONE
        }
    }

    private fun extractParameterNames(method: Method): List<String> {
        val kotlinFunction = method.kotlinFunction
        val kotlinParams = kotlinFunction?.parameters
            ?.filter { it.kind == KParameter.Kind.VALUE }
            ?: emptyList()

        return method.parameters
            .filter { it.type != Continuation::class.java }
            .filter { !Pageable::class.java.isAssignableFrom(it.type) }
            .filter { !Sort::class.java.isAssignableFrom(it.type) }
            .mapIndexed { index, param ->
                param.getAnnotation(Param::class.java)?.value
                    ?: kotlinParams.getOrNull(index)?.name
                    ?: param.name
            }
    }

    private fun hasPageableParameter(method: Method): Boolean {
        val params = method.parameterTypes
        val lastNonContinuationIndex = params.size - 2
        if (lastNonContinuationIndex < 0) return false
        return Pageable::class.java.isAssignableFrom(params[lastNonContinuationIndex])
    }

    private fun hasSortParameter(method: Method): Boolean {
        val params = method.parameterTypes
        val lastNonContinuationIndex = params.size - 2
        if (lastNonContinuationIndex < 0) return false
        return Sort::class.java.isAssignableFrom(params[lastNonContinuationIndex])
    }

    // ============================================
    // 반환 타입 분석
    // ============================================

    private fun isPageReturnType(method: Method): Boolean {
        val actualReturnType = extractActualReturnType(method) ?: return false
        return isAssignableToRawType(actualReturnType, Page::class.java)
    }

    private fun isSliceReturnType(method: Method): Boolean {
        val actualReturnType = extractActualReturnType(method) ?: return false
        return isAssignableToRawType(actualReturnType, Slice::class.java) &&
                !isAssignableToRawType(actualReturnType, Page::class.java)
    }

    private fun isListReturnType(method: Method): Boolean {
        val actualReturnType = extractActualReturnType(method) ?: return false
        return isAssignableToRawType(actualReturnType, List::class.java) ||
                isAssignableToRawType(actualReturnType, Collection::class.java)
    }

    /**
     * suspend 함수의 실제 반환 타입을 추출합니다.
     * Kotlin suspend 함수는 Continuation<? super T>로 컴파일됩니다.
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

    private fun unwrapWildcard(type: Type): Type {
        return when (type) {
            is WildcardType -> {
                type.lowerBounds.firstOrNull() ?: type.upperBounds.firstOrNull() ?: type
            }

            else -> type
        }
    }

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

    // ============================================
    // COUNT 쿼리 생성
    // ============================================

    private fun generateCountQuery(query: String): String {
        val normalized = query.trim()

        return when {
            normalized.startsWith("FROM", ignoreCase = true) ->
                "SELECT COUNT(*) $normalized".replace(ORDER_BY_REGEX, "")

            normalized.startsWith("SELECT", ignoreCase = true) ->
                normalized
                    .replaceFirst(SELECT_FROM_REGEX, "SELECT COUNT(*) FROM")
                    .replace(ORDER_BY_REGEX, "")

            else -> throw IllegalStateException("Cannot generate count query from: $query")
        }
    }
}
