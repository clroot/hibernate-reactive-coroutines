package io.clroot.hibernate.reactive.spring.boot.repository.query

import org.springframework.data.repository.query.parser.PartTree
import java.lang.reflect.Method

/**
 * 애플리케이션 시작 시 파싱된 쿼리 메서드 정보.
 *
 * PartTree 파싱 결과와 생성된 HQL을 캐싱하여 런타임 오버헤드를 제거합니다.
 *
 * @param method 원본 메서드
 * @param partTree 파싱된 PartTree (@Query 메서드면 null)
 * @param hql 생성된 HQL 쿼리 또는 @Query의 쿼리
 * @param countHql Page 반환 타입일 때 사용할 COUNT HQL (null이면 COUNT 불필요)
 * @param parameterBinders 파라미터별 바인더 (LIKE 패턴 변환 등, @Query면 빈 리스트)
 * @param returnType 반환 타입 정보
 * @param isAnnotatedQuery @Query 어노테이션 사용 여부
 * @param isNativeQuery 네이티브 쿼리 여부
 * @param isModifying @Modifying 어노테이션 사용 여부
 * @param parameterStyle 파라미터 바인딩 스타일 (NAMED, POSITIONAL, NONE)
 * @param parameterNames Named Parameter 사용 시 파라미터 이름 목록
 */
data class PreparedQueryMethod(
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
)
