# 쿼리 메서드 자동 생성 설계

> Issue: #2 feat: 쿼리 메서드 자동 생성

## 결정 사항

| 항목 | 결정 |
|------|------|
| 키워드 범위 | 전체 (이슈에 나열된 모든 키워드) |
| 반환 타입 | 단일, List, Boolean, Long |
| 파싱 시점 | 애플리케이션 시작 시 |
| 쿼리 생성 | HQL 문자열 |
| 파서 | **spring-data-commons의 PartTree 사용** |

## 의존성 추가

```kotlin
// build.gradle.kts
implementation("org.springframework.data:spring-data-commons")
```

Spring Data Commons의 `PartTree`와 `Part.Type`을 재사용하여 메서드 이름 파싱을 처리합니다.
- 10년+ 검증된 파서
- 모든 엣지 케이스 처리 완료
- 우리는 HQL 생성과 쿼리 실행만 구현

## 전체 아키텍처

```
┌─────────────────────────────────────────────────────────┐
│                  Repository Interface                    │
│   interface UserRepository : CoroutineCrudRepository<User, Long> │
│     suspend fun findByEmailAndStatus(...)                │
└─────────────────────────┬───────────────────────────────┘
                          │ 시작 시 스캔
                          ▼
┌─────────────────────────────────────────────────────────┐
│          PartTree (spring-data-commons)                  │
│   "findByEmailAndStatus" → PartTree 객체                 │
│   - subject: find                                        │
│   - parts: [email SIMPLE_PROPERTY, status SIMPLE_PROPERTY]│
└─────────────────────────┬───────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│              PartTreeHqlBuilder (직접 구현)               │
│   PartTree → "FROM User WHERE email = :p0               │
│               AND status = :p1"                          │
└─────────────────────────┬───────────────────────────────┘
                          │ 캐싱
                          ▼
┌─────────────────────────────────────────────────────────┐
│              QueryMethodInvoker (직접 구현)               │
│   - HQL 실행                                             │
│   - 파라미터 바인딩                                       │
│   - 반환 타입 변환                                        │
└─────────────────────────────────────────────────────────┘
```

**핵심 컴포넌트:**

1. **PartTree** (spring-data-commons): 메서드 이름 → 구조화된 PartTree 객체
2. **PartTreeHqlBuilder** (직접 구현): PartTree → HQL 문자열
3. **QueryMethodInvoker** (직접 구현): 실제 쿼리 실행 및 결과 변환

## PartTree (spring-data-commons) 활용

### 핵심 클래스

Spring Data Commons에서 제공하는 클래스들을 활용합니다:

```kotlin
// spring-data-commons 제공
import org.springframework.data.repository.query.parser.PartTree
import org.springframework.data.repository.query.parser.Part
import org.springframework.data.repository.query.parser.Part.Type

// 사용 예시
val partTree = PartTree("findByEmailAndStatus", User::class.java)
partTree.isCountProjection  // false
partTree.isExistsProjection // false
partTree.isDelete           // false
partTree.sort               // Sort 정보

for (orPart in partTree) {
    for (part in orPart) {
        part.property      // PropertyPath (email, status)
        part.type          // Part.Type (SIMPLE_PROPERTY, LIKE, etc.)
    }
}
```

### Part.Type enum (spring-data-commons 제공)

| Part.Type | 키워드 | 파라미터 수 |
|-----------|--------|------------|
| SIMPLE_PROPERTY | Is, Equals, 또는 생략 | 1 |
| LIKE | Like | 1 |
| NOT_LIKE | NotLike | 1 |
| STARTING_WITH | StartingWith | 1 |
| ENDING_WITH | EndingWith | 1 |
| CONTAINING | Containing | 1 |
| BETWEEN | Between | 2 |
| LESS_THAN | LessThan | 1 |
| GREATER_THAN | GreaterThan | 1 |
| IN | In | 1 (Collection) |
| NOT_IN | NotIn | 1 (Collection) |
| IS_NULL | IsNull | 0 |
| IS_NOT_NULL | IsNotNull | 0 |
| TRUE | True | 0 |
| FALSE | False | 0 |

### 파싱 예시

| 메서드 이름 | PartTree 결과 |
|------------|---------------|
| `findByEmail` | parts=[[email SIMPLE_PROPERTY]] |
| `findByNameAndAge` | parts=[[name SIMPLE_PROPERTY, age SIMPLE_PROPERTY]] |
| `findByEmailLike` | parts=[[email LIKE]] |
| `findByNameOrEmail` | parts=[[name SIMPLE_PROPERTY], [email SIMPLE_PROPERTY]] |
| `existsByEmail` | isExistsProjection=true, parts=[[email SIMPLE_PROPERTY]] |
| `countByStatus` | isCountProjection=true, parts=[[status SIMPLE_PROPERTY]] |

## PartTreeHqlBuilder 상세 (직접 구현)

### Subject별 HQL 템플릿

| Subject | HQL 템플릿 |
|---------|-----------|
| FIND | `FROM {Entity} WHERE {conditions} [ORDER BY {orderBy}]` |
| EXISTS | `SELECT COUNT(e) > 0 FROM {Entity} e WHERE {conditions}` |
| COUNT | `SELECT COUNT(e) FROM {Entity} e WHERE {conditions}` |
| DELETE | `DELETE FROM {Entity} WHERE {conditions}` |

### Operator별 조건 생성

| Operator | HQL 조건 | 파라미터 수 |
|----------|----------|------------|
| EQUALS | `{prop} = :p{n}` | 1 |
| LIKE | `{prop} LIKE :p{n}` | 1 |
| STARTING_WITH | `{prop} LIKE :p{n}` | 1 (값에 `%` 붙임) |
| ENDING_WITH | `{prop} LIKE :p{n}` | 1 (값 앞에 `%` 붙임) |
| CONTAINING | `{prop} LIKE :p{n}` | 1 (양쪽에 `%` 붙임) |
| BETWEEN | `{prop} BETWEEN :p{n} AND :p{n+1}` | 2 |
| IN | `{prop} IN :p{n}` | 1 (Collection) |
| IS_NULL | `{prop} IS NULL` | 0 |
| IS_NOT_NULL | `{prop} IS NOT NULL` | 0 |
| TRUE | `{prop} = TRUE` | 0 |
| FALSE | `{prop} = FALSE` | 0 |

### 생성 예시

```kotlin
// findByEmailAndStatus(email: String, status: Status)
"FROM User WHERE email = :p0 AND status = :p1"

// findByNameContainingOrderByCreatedAtDesc(name: String)
"FROM User WHERE name LIKE :p0 ORDER BY createdAt DESC"
// 실행 시 name 값을 "%{value}%"로 변환

// findByAgeBetween(min: Int, max: Int)
"FROM User WHERE age BETWEEN :p0 AND :p1"
```

## 기존 코드와의 통합

### SimpleHibernateReactiveRepository 변경

```kotlin
class SimpleHibernateReactiveRepository<T, ID>(
    ...
    private val queryMethods: Map<String, PreparedQueryMethod>,  // 시작 시 주입
) : InvocationHandler {

    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any {
        when (method.name) {
            "save", "findById", ... -> // 기본 CRUD
            else -> {
                // 커스텀 쿼리 메서드 처리
                val prepared = queryMethods[method.name]
                    ?: throw UnsupportedOperationException(...)
                return invokeQueryMethod(prepared, args, continuation)
            }
        }
    }
}

data class PreparedQueryMethod(
    val hql: String,              // 미리 생성된 HQL
    val queryMethod: QueryMethod, // 파싱 결과
    val parameterBinders: List<ParameterBinder>, // 파라미터 변환기
)
```

### 초기화 흐름

```
HibernateReactiveRepositoryFactoryBean.getObject()
  ├─ 1. Repository 인터페이스의 모든 메서드 스캔
  ├─ 2. 기본 CRUD가 아닌 메서드 필터링
  ├─ 3. PartTree로 파싱 (실패 시 예외 → 앱 시작 실패)
  ├─ 4. PartTreeHqlBuilder로 HQL 생성
  ├─ 5. PreparedQueryMethod Map 생성
  └─ 6. SimpleHibernateReactiveRepository에 주입
```

## 검증 및 에러 처리

### 시작 시 검증 항목

| 검증 항목 | 예시 | 에러 메시지 |
|----------|------|------------|
| 알 수 없는 접두사 | `searchByEmail` | `Unknown query subject: search` |
| 존재하지 않는 프로퍼티 | `findByEmali` (오타) | `No property 'emali' found on User` |
| 파라미터 개수 불일치 | `findByNameAndAge(name)` | `Expected 2 parameters but got 1` |
| 잘못된 반환 타입 | `fun countByStatus(): String` | `countBy must return Long` |
| Between 파라미터 부족 | `findByAgeBetween(age: Int)` | `Between requires 2 parameters` |

### 예외 클래스

```kotlin
class QueryMethodParseException(
    val repositoryInterface: Class<*>,
    val methodName: String,
    override val message: String,
) : RuntimeException(
    "Failed to parse query method '${repositoryInterface.simpleName}.$methodName': $message"
)
```

프로퍼티 오타의 경우 Levenshtein 거리로 유사한 프로퍼티 이름을 추천합니다.

## 파일 구조

```
hibernate-reactive-coroutines-spring-boot-starter/
└── src/main/kotlin/io/clroot/hibernate/reactive/spring/boot/
    └── repository/
        ├── SimpleHibernateReactiveRepository.kt      (수정)
        ├── HibernateReactiveRepositoryFactoryBean.kt (수정)
        │
        └── query/                           (새 패키지)
            ├── PartTreeHqlBuilder.kt        # PartTree → HQL 변환
            ├── QueryMethodInvoker.kt        # 쿼리 실행
            ├── PreparedQueryMethod.kt       # 캐싱된 쿼리 메서드
            └── ParameterBinder.kt           # 파라미터 바인딩 (LIKE % 처리 등)
```

**spring-data-commons에서 가져오는 것:**
- `PartTree` - 메서드 이름 파싱
- `Part`, `Part.Type` - 조건 정보
- `PropertyPath` - 프로퍼티 경로

## 테스트 전략

| 테스트 종류 | 대상 | 중점 |
|------------|------|------|
| 유닛 테스트 | PartTreeHqlBuilder | Part.Type별 올바른 HQL 생성 |
| 유닛 테스트 | ParameterBinder | LIKE 패턴 변환 등 |
| 통합 테스트 | 전체 흐름 | 실제 DB 쿼리 실행 |

### PartTreeHqlBuilder 테스트 케이스

```
findByEmail → "FROM User WHERE email = :p0"
findByNameAndAge → "FROM User WHERE name = :p0 AND age = :p1"
findByNameOrEmail → "FROM User WHERE name = :p0 OR email = :p1"
findByEmailContaining → "FROM User WHERE email LIKE :p0"  // %value%
findByAgeBetween → "FROM User WHERE age BETWEEN :p0 AND :p1"
findByActiveTrue → "FROM User WHERE active = TRUE"
findByDeletedAtIsNull → "FROM User WHERE deletedAt IS NULL"
existsByEmail → "SELECT COUNT(e) > 0 FROM User e WHERE e.email = :p0"
countByStatus → "SELECT COUNT(e) FROM User e WHERE e.status = :p0"
deleteByEmail → "DELETE FROM User WHERE email = :p0"
```
