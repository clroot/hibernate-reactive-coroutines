package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.spring.boot.repository.query.CountQueryDeriver
import io.clroot.hibernate.reactive.spring.boot.repository.query.Modifying
import io.clroot.hibernate.reactive.spring.boot.repository.query.Param
import io.clroot.hibernate.reactive.spring.boot.repository.query.ParameterStyle
import io.clroot.hibernate.reactive.spring.boot.repository.query.PartTreeHqlBuilder
import io.clroot.hibernate.reactive.spring.boot.repository.query.PreparedQueryMethod
import io.clroot.hibernate.reactive.spring.boot.repository.query.Query
import io.clroot.hibernate.reactive.spring.boot.repository.query.QueryParameterParser
import io.clroot.hibernate.reactive.spring.boot.repository.query.QueryParameters
import io.clroot.hibernate.reactive.spring.boot.repository.query.QueryReturnType
import io.clroot.hibernate.reactive.spring.boot.repository.query.QueryStatementType
import jakarta.persistence.Tuple
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.domain.Sort
import org.springframework.data.repository.query.parser.PartTree
import java.lang.reflect.Method
import java.lang.reflect.Modifier
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
    private val entityName: String = entityClass.simpleName,
) {

    companion object {
        /** CoroutineCrudRepository의 기본 메서드 이름들 */
        private val BASE_METHODS = setOf(
            "save", "saveAll",
            "findById", "findAll", "findAllById",
            "existsById", "count",
            "deleteById", "delete", "deleteAllById", "deleteAll",
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
    fun isCustomQueryMethod(method: Method): Boolean =
        isDeclaredRepositoryMethod(method) && isSuspendMethod(method)

    /**
     * 사용자가 Repository 인터페이스에 직접 선언한 메서드인지 확인합니다.
     *
     * 기본 CRUD 메서드, `Object` 메서드, default/static/bridge 메서드는 제외합니다.
     */
    fun isDeclaredRepositoryMethod(method: Method): Boolean {
        if (method.name in BASE_METHODS) return false
        if (method.declaringClass == Any::class.java) return false
        if (method.isDefault) return false
        if (method.isSynthetic || method.isBridge) return false
        if (Modifier.isStatic(method.modifiers)) return false
        return true
    }

    /**
     * suspend 함수인지 확인합니다 (마지막 파라미터가 Continuation).
     */
    fun isSuspendMethod(method: Method): Boolean {
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
        val countHql = generateCountHqlIfNeeded(returnType, queryAnnotation, query)
        val queryParameters = QueryParameterParser.parse(query)
        val countParameters = countHql
            ?.let(QueryParameterParser::parse)
            ?: QueryParameters(ParameterStyle.NONE)
        val argumentNames = extractParameterNames(method)
        validateQueryParameters(method, queryParameters, countParameters, argumentNames)

        val prepared = PreparedQueryMethod(
            method = method,
            partTree = null,
            hql = query,
            countHql = countHql,
            parameterBinders = emptyList(),
            returnType = returnType,
            isAnnotatedQuery = true,
            isNativeQuery = queryAnnotation.nativeQuery,
            isModifying = isModifying,
            parameterStyle = queryParameters.style,
            parameterNames = argumentNames,
        )
        validateAnnotatedResultType(prepared)
        return prepared
    }

    private fun determineAnnotatedReturnType(method: Method, isModifying: Boolean): QueryReturnType {
        return when {
            isModifying -> determineModifyingReturnType(method)
            isPageReturnType(method) -> QueryReturnType.PAGE
            isSliceReturnType(method) -> QueryReturnType.SLICE
            isListReturnType(method) -> QueryReturnType.LIST
            else -> QueryReturnType.SINGLE
        }
    }

    private fun determineModifyingReturnType(method: Method): QueryReturnType {
        val actualReturnType = extractActualReturnType(method)
        return when {
            actualReturnType != null &&
                    isAssignableToRawType(actualReturnType, Int::class.javaObjectType) -> QueryReturnType.MODIFYING

            actualReturnType != null &&
                    isAssignableToRawType(actualReturnType, Unit::class.java) -> QueryReturnType.VOID

            else -> throw IllegalStateException(
                "@Modifying method '${method.name}' must return Int or Unit",
            )
        }
    }

    private fun generateCountHqlIfNeeded(
        returnType: QueryReturnType,
        queryAnnotation: Query,
        query: String,
    ): String? {
        if (returnType != QueryReturnType.PAGE) return null

        return when {
            queryAnnotation.countQuery.isNotBlank() -> queryAnnotation.countQuery
            queryAnnotation.nativeQuery -> throw IllegalStateException(
                "Native query with Page return type requires explicit countQuery",
            )

            else -> CountQueryDeriver.derive(query)
        }
    }

    private fun validateQueryAnnotation(method: Method, queryAnnotation: Query) {
        val query = queryAnnotation.value
        val isModifying = method.isAnnotationPresent(Modifying::class.java)
        val statementType = CountQueryDeriver.statementType(query)

        if (statementType == QueryStatementType.UNKNOWN) {
            throw IllegalStateException(
                "Method '${method.name}' has an unsupported or unrecognized @Query statement",
            )
        }
        if (isModifying && statementType == QueryStatementType.SELECT) {
            throw IllegalStateException(
                "@Modifying method '${method.name}' cannot have SELECT query",
            )
        }
        if (!isModifying && statementType == QueryStatementType.MODIFYING) {
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

        val maxResults = partTree.maxResults
        if (maxResults != null && hasPageable) {
            throw IllegalStateException(
                "Method '${method.name}' combines a Top/First limit with a Pageable parameter, " +
                        "which is ambiguous. Use either the limit keyword or Pageable, not both.",
            )
        }

        val builder = PartTreeHqlBuilder(entityName, partTree)
        val buildResult = if (hasPageable || hasSort) {
            builder.buildWithSort(null)
        } else {
            builder.build()
        }

        val declaredArgumentCount = queryArgumentCount(method)
        if (buildResult.parameterBinders.size != declaredArgumentCount) {
            throw IllegalStateException(
                "Method '${method.name}' derives ${buildResult.parameterBinders.size} query parameter(s) " +
                        "from its name but declares $declaredArgumentCount argument(s)",
            )
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
            maxResults = maxResults,
        )
    }

    private fun determinePartTreeReturnType(method: Method, partTree: PartTree): QueryReturnType {
        return when {
            partTree.isExistsProjection -> QueryReturnType.BOOLEAN
            partTree.isCountProjection -> QueryReturnType.LONG
            partTree.isDelete -> determineDeleteReturnType(method)
            isPageReturnType(method) -> QueryReturnType.PAGE
            isSliceReturnType(method) -> QueryReturnType.SLICE
            isListReturnType(method) -> QueryReturnType.LIST
            else -> QueryReturnType.SINGLE
        }
    }

    /**
     * 파생 `deleteBy...` 메서드의 반환 타입을 결정합니다.
     *
     * Spring Data와 동일하게 `Unit`, `Int`, `Long` 반환을 지원합니다.
     */
    private fun determineDeleteReturnType(method: Method): QueryReturnType {
        val actualReturnType = extractActualReturnType(method) ?: return QueryReturnType.VOID
        return when {
            isAssignableToRawType(actualReturnType, Int::class.javaObjectType) -> QueryReturnType.MODIFYING
            isAssignableToRawType(actualReturnType, Long::class.javaObjectType) -> QueryReturnType.LONG
            isAssignableToRawType(actualReturnType, Unit::class.java) -> QueryReturnType.VOID
            else -> throw IllegalStateException(
                "Derived delete method '${method.name}' must return Unit, Int or Long",
            )
        }
    }

    // ============================================
    // 파라미터 분석
    // ============================================

    private fun validateQueryParameters(
        method: Method,
        query: QueryParameters,
        count: QueryParameters,
        argumentNames: List<String>,
    ) {
        val duplicateName = argumentNames
            .groupingBy { it }
            .eachCount()
            .entries
            .firstOrNull { it.value > 1 }
            ?.key
        if (duplicateName != null) {
            throw IllegalStateException(
                "Method '${method.name}' declares duplicate query parameter name '$duplicateName'",
            )
        }

        if (query.style != ParameterStyle.NONE && count.style != ParameterStyle.NONE &&
            query.style != count.style
        ) {
            throw IllegalStateException(
                "Query and countQuery for method '${method.name}' use different parameter styles",
            )
        }

        val countOnlyName = count.names.firstOrNull { it !in query.names }
        if (countOnlyName != null) {
            throw IllegalStateException(
                "countQuery for method '${method.name}' references parameter '$countOnlyName' " +
                        "that is not used by the content query",
            )
        }
        val countOnlyPosition = count.positions.firstOrNull { it !in query.positions }
        if (countOnlyPosition != null) {
            throw IllegalStateException(
                "countQuery for method '${method.name}' references parameter ?$countOnlyPosition " +
                        "that is not used by the content query",
            )
        }

        val unknownNames = (query.names + count.names).distinct() - argumentNames.toSet()
        if (unknownNames.isNotEmpty()) {
            throw IllegalStateException(
                "Query for method '${method.name}' references unknown parameter '${unknownNames.first()}'",
            )
        }

        val argumentCount = argumentNames.size
        val invalidPosition = (query.positions + count.positions)
            .firstOrNull { it > argumentCount }
        if (invalidPosition != null) {
            throw IllegalStateException(
                "Query for method '${method.name}' references positional parameter ?$invalidPosition " +
                        "but has only $argumentCount query arguments",
            )
        }
    }

    /**
     * 쿼리 인자 개수를 셉니다. Continuation과 Pageable/Sort는 쿼리 인자가 아닙니다.
     */
    private fun queryArgumentCount(method: Method): Int =
        method.parameters.count { param ->
            param.type != Continuation::class.java &&
                    !Pageable::class.java.isAssignableFrom(param.type) &&
                    !Sort::class.java.isAssignableFrom(param.type)
        }

    /**
     * Hibernate가 직접 생성할 수 없는 프로젝션 형태를 기동 시점에 거부합니다.
     *
     * 스칼라와 HQL 생성자 DTO는 선언된 결과 클래스를 typed query에 전달합니다. Spring Data식
     * 인터페이스 프로젝션은 별도 프록시 생성이 필요하고, Tuple/배열 다중 선택은 별도 매핑
     * 규칙이 필요하므로 이 기능의 범위에 포함하지 않습니다.
     */
    private fun validateAnnotatedResultType(prepared: PreparedQueryMethod) {
        if (prepared.returnType == QueryReturnType.MODIFYING || prepared.returnType == QueryReturnType.VOID) return

        val resultClass = prepared.resultClass ?: throw IllegalStateException(
            "Could not determine the result type of @Query method '${prepared.method.name}'",
        )
        if (resultClass.isAssignableFrom(entityClass)) return

        if (resultClass.isInterface) {
            throw IllegalStateException(
                "@Query method '${prepared.method.name}' declares interface projection " +
                        "${resultClass.simpleName}, which is not supported. Use a constructor DTO projection instead.",
            )
        }
        if (resultClass.isArray || resultClass == Tuple::class.java) {
            throw IllegalStateException(
                "@Query method '${prepared.method.name}' declares Tuple/array projection " +
                        "${resultClass.simpleName}, which is not supported. Use a scalar or constructor DTO projection instead.",
            )
        }
    }

    private fun extractParameterNames(method: Method): List<String> {
        val kotlinFunction = method.kotlinFunction
        val kotlinParams = kotlinFunction?.parameters
            ?.filter { it.kind == KParameter.Kind.VALUE }
            ?: emptyList()

        return method.parameters
            .mapIndexed { index, param -> param to kotlinParams.getOrNull(index)?.name }
            .filter { (param) -> param.type != Continuation::class.java }
            .filter { (param) -> !Pageable::class.java.isAssignableFrom(param.type) }
            .filter { (param) -> !Sort::class.java.isAssignableFrom(param.type) }
            .map { (param, kotlinName) ->
                param.getAnnotation(Param::class.java)?.value
                    ?: kotlinName
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

}
