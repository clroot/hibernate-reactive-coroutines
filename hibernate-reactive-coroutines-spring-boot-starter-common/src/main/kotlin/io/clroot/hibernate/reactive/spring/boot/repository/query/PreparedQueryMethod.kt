package io.clroot.hibernate.reactive.spring.boot.repository.query

import io.clroot.hibernate.reactive.repository.query.QueryParameterParser
import io.clroot.hibernate.reactive.repository.query.QueryParameterStyle
import io.clroot.hibernate.reactive.repository.query.derived.ParameterBinding
import io.clroot.hibernate.reactive.repository.runtime.PreparedRepositoryQuery
import io.clroot.hibernate.reactive.repository.runtime.RepositoryQueryKind
import io.clroot.hibernate.reactive.repository.runtime.RepositoryQueryReturnType
import kotlin.coroutines.Continuation
import org.springframework.data.repository.query.parser.PartTree
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/**
 * 애플리케이션 시작 시 파싱된 쿼리 메서드 정보.
 *
 * 파생 쿼리 파싱 결과와 생성된 HQL을 캐싱하여 런타임 오버헤드를 제거합니다.
 *
 * @param method 원본 메서드
 * @param partTree 호환성을 위해 보존된 PartTree (@Query 메서드면 null)
 * @param hql 생성된 HQL 쿼리 또는 @Query의 쿼리
 * @param countHql Page 반환 타입일 때 사용할 COUNT HQL (null이면 COUNT 불필요)
 * @param parameterBinders 파라미터별 바인더 (LIKE 패턴 변환 등, @Query면 빈 리스트)
 * @param returnType 반환 타입 정보
 * @param isAnnotatedQuery @Query 어노테이션 사용 여부
 * @param isNativeQuery 네이티브 쿼리 여부
 * @param isModifying @Modifying 어노테이션 사용 여부
 * @param parameterStyle 파라미터 바인딩 스타일 (NAMED, POSITIONAL, NONE)
 * @param parameterNames Named Parameter 사용 시 파라미터 이름 목록
 * @param maxResults `Top`/`First` 키워드로 지정된 최대 결과 개수 (제한이 없으면 null)
 */
public data class PreparedQueryMethod(
    val method: Method,
    val partTree: PartTree?,
    val hql: String,
    val countHql: String?,
    val parameterBinders: List<ParameterBinder>,
    val returnType: QueryReturnType,
    val isAnnotatedQuery: Boolean = false,
    val isNativeQuery: Boolean = false,
    val isModifying: Boolean = false,
    val parameterStyle: ParameterStyle = ParameterStyle.NONE,
    val parameterNames: List<String> = emptyList(),
    val maxResults: Int? = null,
) {
    internal val annotatedParameters: QueryParameters by lazy(LazyThreadSafetyMode.PUBLICATION) {
        QueryParameterParser.parse(hql).toSpringParameters()
    }

    internal val countAnnotatedParameters: QueryParameters by lazy(LazyThreadSafetyMode.PUBLICATION) {
        countHql
            ?.let(QueryParameterParser::parse)
            ?.toSpringParameters()
            ?: QueryParameters(ParameterStyle.NONE)
    }

    /**
     * 선언된 suspend 반환 타입에서 추출한 실제 쿼리 결과 클래스입니다.
     *
     * 단일 결과는 반환 타입 자체를, List/Page/Slice는 요소 타입을 사용합니다. 생성자 파라미터를
     * 추가하지 않아 공개 data class의 기존 생성자 ABI를 유지합니다.
     */
    internal val resultClass: Class<*>? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        resolveResultClass(method, returnType)
    }
}

private fun resolveResultClass(method: Method, returnType: QueryReturnType): Class<*>? {
    if (returnType !in RESULT_BEARING_TYPES) return null

    val actualReturnType = extractSuspendReturnType(method) ?: return null
    val resultType = if (returnType == QueryReturnType.SINGLE) {
        actualReturnType
    } else {
        firstTypeArgument(actualReturnType) ?: return null
    }

    return rawClassOf(resultType)
}

private fun extractSuspendReturnType(method: Method): Type? {
    val continuationType = method.genericParameterTypes.lastOrNull() as? ParameterizedType ?: return null
    if (continuationType.rawType != Continuation::class.java) return null
    return continuationType.actualTypeArguments.firstOrNull()?.let(::unwrapWildcard)
}

private fun firstTypeArgument(type: Type): Type? =
    (type as? ParameterizedType)?.actualTypeArguments?.firstOrNull()?.let(::unwrapWildcard)

private fun rawClassOf(type: Type): Class<*>? = when (type) {
    is Class<*> -> type
    is ParameterizedType -> type.rawType as? Class<*>
    else -> null
}

private fun unwrapWildcard(type: Type): Type = when (type) {
    is WildcardType -> type.lowerBounds.firstOrNull() ?: type.upperBounds.firstOrNull() ?: type
    else -> type
}

private val RESULT_BEARING_TYPES = setOf(
    QueryReturnType.SINGLE,
    QueryReturnType.LIST,
    QueryReturnType.PAGE,
    QueryReturnType.SLICE,
)

internal data class QueryParameters(
    val style: ParameterStyle,
    val names: List<String> = emptyList(),
    val positions: List<Int> = emptyList(),
)

private fun io.clroot.hibernate.reactive.repository.query.QueryParameters.toSpringParameters(): QueryParameters =
    QueryParameters(
        style = when (style) {
            QueryParameterStyle.NAMED -> ParameterStyle.NAMED
            QueryParameterStyle.POSITIONAL -> ParameterStyle.POSITIONAL
            QueryParameterStyle.NONE -> ParameterStyle.NONE
        },
        names = names,
        positions = positions,
    )

internal fun PreparedQueryMethod.toRuntimeQuery(): PreparedRepositoryQuery = PreparedRepositoryQuery(
    methodName = method.name,
    hql = hql,
    countHql = countHql,
    parameterBindings = parameterBinders.map { binder ->
        when (binder) {
            ParameterBinder.Direct -> ParameterBinding.DIRECT
            ParameterBinder.Containing -> ParameterBinding.CONTAINING
            ParameterBinder.StartingWith -> ParameterBinding.STARTING_WITH
            ParameterBinder.EndingWith -> ParameterBinding.ENDING_WITH
            ParameterBinder.InCollection -> ParameterBinding.IN_COLLECTION
            ParameterBinder.NotInCollection -> ParameterBinding.NOT_IN_COLLECTION
        }
    },
    returnType = when (returnType) {
        QueryReturnType.SINGLE -> RepositoryQueryReturnType.SINGLE
        QueryReturnType.LIST -> RepositoryQueryReturnType.LIST
        QueryReturnType.BOOLEAN -> RepositoryQueryReturnType.BOOLEAN
        QueryReturnType.LONG -> RepositoryQueryReturnType.LONG
        QueryReturnType.VOID -> RepositoryQueryReturnType.VOID
        QueryReturnType.PAGE -> RepositoryQueryReturnType.PAGE
        QueryReturnType.SLICE -> RepositoryQueryReturnType.SLICE
        QueryReturnType.MODIFYING -> RepositoryQueryReturnType.MODIFYING
    },
    queryKind = if (isAnnotatedQuery) RepositoryQueryKind.ANNOTATED else RepositoryQueryKind.DERIVED,
    isNativeQuery = isNativeQuery,
    isModifying = isModifying,
    clearAutomatically = method.getAnnotation(Modifying::class.java)?.clearAutomatically == true,
    parameterStyle = when (parameterStyle) {
        ParameterStyle.NAMED -> QueryParameterStyle.NAMED
        ParameterStyle.POSITIONAL -> QueryParameterStyle.POSITIONAL
        ParameterStyle.NONE -> QueryParameterStyle.NONE
    },
    parameterNames = parameterNames,
    resultClass = resultClass,
    maxResults = maxResults,
    isDelete = partTree?.isDelete == true,
)
