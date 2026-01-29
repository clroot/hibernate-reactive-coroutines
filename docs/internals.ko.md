# 내부 동작 원리

## 아키텍처 개요

```
┌─────────────────────────────────────────────────────────┐
│                     Service Layer                       │
│  @Transactional / ReactiveTransactionExecutor           │
└─────────────────────────┬───────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────┐
│              TransactionalAwareSessionProvider          │
│  세션 우선순위: @Transactional > ReactiveSessionContext    │
└─────────────────────────┬───────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────┐
│                  Repository Proxy                       │
│  쿼리 메서드 파싱, HQL 생성, 실행                             │
└─────────────────────────┬───────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────┐
│               Hibernate Reactive (Mutiny)               │
│  Mutiny.Session, Mutiny.SessionFactory                  │
└─────────────────────────┬───────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────┐
│                   Vert.x SQL Client                     │
│  비동기 DB 연결, EventLoop 스레드                           │
└─────────────────────────────────────────────────────────┘
```

## 핵심 컴포넌트

### ReactiveSessionContext

CoroutineContext Element로 세션과 트랜잭션 정보를 코루틴 체인에 전파합니다.

```kotlin
data class ReactiveSessionContext(
    val session: Mutiny.Session,
    val isReadOnly: Boolean,
    val timeout: Duration,
) : CoroutineContext.Element
```

### TransactionalAwareSessionProvider

세션 획득 우선순위를 관리합니다:

1. **@Transactional 컨텍스트** - Spring ReactorContext에서 MutinySessionHolder 조회
2. **ReactiveSessionContext** - Kotlin CoroutineContext에서 세션 조회
3. **새 세션 생성** - 컨텍스트가 없으면 새 세션 생성

```kotlin
open suspend fun <T> read(block: (Mutiny.Session) -> Uni<T>): T {
    // 1. @Transactional 컨텍스트 확인
    val transactionalContext = getTransactionalSessionContext()
    if (transactionalContext != null) {
        return withContext(transactionalContext.dispatcher) {
            block(transactionalContext.session).awaitSuspending()
        }
    }

    // 2. ReactiveSessionContext 확인
    val existingContext = currentContextOrNull()
    if (existingContext != null) {
        return block(existingContext.session).awaitSuspending()
    }

    // 3. 새 세션 생성
    return sessionFactory.withSession { session ->
        block(session)
    }.awaitSuspending()
}
```

### HibernateReactiveTransactionManager

Spring의 `ReactiveTransactionManager` 구현체입니다.

```kotlin
class HibernateReactiveTransactionManager(
    private val sessionFactory: Mutiny.SessionFactory,
) : AbstractReactiveTransactionManager()
```

**트랜잭션 흐름:**

```
doBegin()
    │
    ├─ Mutiny.Session 생성
    ├─ Transaction 시작
    ├─ MutinySessionHolder 생성
    └─ TransactionSynchronizationManager에 바인딩
         │
         ▼
    비즈니스 로직 실행
         │
         ▼
doCommit() / doRollback()
    │
    ├─ session.flush()  (Dirty Checking)
    ├─ commitTransaction() 또는 rollbackTransaction()
    └─ 세션 정리
```

### MutinySessionHolder

세션과 Vert.x Context를 함께 보관합니다.

```kotlin
class MutinySessionHolder(
    session: Mutiny.Session,
    vertxContext: io.vertx.core.Context?,  // 스레드 일관성 보장
    mode: TransactionMode,
    timeout: Duration,
) : ResourceHolderSupport()
```

## Vert.x 스레드 일관성

Hibernate Reactive 세션은 특정 Vert.x EventLoop 스레드에 바인딩됩니다. 다른 스레드에서 세션에 접근하면 `HR000069` 에러가 발생합니다.

이 라이브러리는 `MutinySessionHolder`에 `vertxContext`를 저장하고, `withContext(dispatcher)`를 사용하여 항상 올바른 스레드에서 세션 작업이 수행되도록 보장합니다.

```kotlin
// TransactionalAwareSessionProvider 내부
return withContext(transactionalContext.dispatcher ?: currentCoroutineContext()) {
    block(transactionalContext.session).awaitSuspending()
}
```

## Repository 프록시 생성

`@EnableHibernateReactiveRepositories` 또는 Auto-configuration이 활성화되면:

1. `CoroutineCrudRepository`를 상속한 인터페이스 스캔
2. 각 인터페이스에 대해 JDK Dynamic Proxy 생성
3. 메서드 호출 시 `HibernateReactiveRepositoryInvocationHandler`가 처리
4. 메서드 이름 파싱 → HQL 생성 → 실행

```kotlin
class HibernateReactiveRepositoryInvocationHandler(
    private val entityClass: Class<*>,
    private val sessionProvider: TransactionalAwareSessionProvider,
) : InvocationHandler {

    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
        return when {
            method.name.startsWith("findBy") -> handleFindBy(method, args)
            method.name.startsWith("existsBy") -> handleExistsBy(method, args)
            // ...
        }
    }
}
```

## 쿼리 메서드 파싱

메서드 이름이 다음 규칙으로 파싱됩니다:

```
findAllByStatusAndNameContainingOrderByCreatedAtDesc
│      │      │   │           │       │
│      │      │   │           │       └─ 정렬: createdAt DESC
│      │      │   │           └─ 키워드: OrderBy
│      │      │   └─ 키워드: Containing (LIKE %?%)
│      │      └─ 필드: name
│      └─ 키워드: And
└─ 접두사: findAllBy (List 반환)
```

생성되는 HQL:

```sql
SELECT e FROM Entity e
WHERE e.status = ?1 AND e.name LIKE ?2
ORDER BY e.createdAt DESC
```
