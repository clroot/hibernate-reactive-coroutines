# How It Works

**[🇰🇷 한국어](internals.ko.md)**

## Architecture Overview

```
┌────────────────────────────────────────────────────────────┐
│                     Service Layer                          │
│  @Transactional / ReactiveTransactionExecutor              │
└─────────────────────────┬──────────────────────────────────┘
                          │
┌─────────────────────────▼──────────────────────────────────┐
│              TransactionalAwareSessionProvider             │
│  Session priority: @Transactional > ReactiveSessionContext │
└─────────────────────────┬──────────────────────────────────┘
                          │
┌─────────────────────────▼──────────────────────────────────┐
│                  Repository Proxy                          │
│  Query method parsing, HQL generation, execution           │
└─────────────────────────┬──────────────────────────────────┘
                          │
┌─────────────────────────▼──────────────────────────────────┐
│               Hibernate Reactive (Mutiny)                  │
│  Mutiny.Session, Mutiny.SessionFactory                     │
└─────────────────────────┬──────────────────────────────────┘
                          │
┌─────────────────────────▼──────────────────────────────────┐
│                   Vert.x SQL Client                        │
│  Async DB connection, EventLoop thread                     │
└────────────────────────────────────────────────────────────┘
```

## Core Components

### ReactiveSessionContext

A CoroutineContext Element that propagates session and transaction information through the coroutine chain.

```kotlin
data class ReactiveSessionContext(
    val session: Mutiny.Session,
    val isReadOnly: Boolean,
    val timeout: Duration,
) : CoroutineContext.Element
```

### TransactionalAwareSessionProvider

Manages session acquisition priority:

1. **@Transactional context** - Retrieves MutinySessionHolder from Spring ReactorContext
2. **ReactiveSessionContext** - Retrieves session from Kotlin CoroutineContext
3. **New session** - Creates a new session if no context exists

```kotlin
open suspend fun <T> read(block: (Mutiny.Session) -> Uni<T>): T {
    // 1. Check @Transactional context
    val transactionalContext = getTransactionalSessionContext()
    if (transactionalContext != null) {
        return withContext(transactionalContext.dispatcher) {
            block(transactionalContext.session).awaitSuspending()
        }
    }

    // 2. Check ReactiveSessionContext
    val existingContext = currentContextOrNull()
    if (existingContext != null) {
        return block(existingContext.session).awaitSuspending()
    }

    // 3. Create new session
    return sessionFactory.withSession { session ->
        block(session)
    }.awaitSuspending()
}
```

### HibernateReactiveTransactionManager

Spring's `ReactiveTransactionManager` implementation.

```kotlin
class HibernateReactiveTransactionManager(
    private val sessionFactory: Mutiny.SessionFactory,
) : AbstractReactiveTransactionManager()
```

**Transaction Flow:**

```
doBegin()
    │
    ├─ Create Mutiny.Session
    ├─ Start Transaction
    ├─ Create MutinySessionHolder
    └─ Bind to TransactionSynchronizationManager
         │
         ▼
    Execute business logic
         │
         ▼
doCommit() / doRollback()
    │
    ├─ session.flush()  (Dirty Checking)
    ├─ commitTransaction() or rollbackTransaction()
    └─ Cleanup session
```

### MutinySessionHolder

Holds the session along with Vert.x Context.

```kotlin
class MutinySessionHolder(
    session: Mutiny.Session,
    vertxContext: io.vertx.core.Context?,  // Ensures thread consistency
    mode: TransactionMode,
    timeout: Duration,
) : ResourceHolderSupport()
```

## Vert.x Thread Consistency

Hibernate Reactive sessions are bound to a specific Vert.x EventLoop thread. Accessing a session from a different thread results in the `HR000069` error.

This library stores `vertxContext` in `MutinySessionHolder` and uses `withContext(dispatcher)` to ensure session operations always run on the correct thread.

```kotlin
// Inside TransactionalAwareSessionProvider
return withContext(transactionalContext.dispatcher ?: currentCoroutineContext()) {
    block(transactionalContext.session).awaitSuspending()
}
```

## Repository Proxy Generation

When `@EnableHibernateReactiveRepositories` or Auto-configuration is enabled:

1. Scan interfaces extending `CoroutineCrudRepository`
2. Create JDK Dynamic Proxy for each interface
3. Method calls are handled by `HibernateReactiveRepositoryInvocationHandler`
4. Parse method name → Generate HQL → Execute

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

## Query Method Parsing

Method names are parsed according to these rules:

```
findAllByStatusAndNameContainingOrderByCreatedAtDesc
│      │      │   │           │       │
│      │      │   │           │       └─ Sort: createdAt DESC
│      │      │   │           └─ Keyword: OrderBy
│      │      │   └─ Keyword: Containing (LIKE %?%)
│      │      └─ Field: name
│      └─ Keyword: And
└─ Prefix: findAllBy (returns List)
```

Generated HQL:

```sql
SELECT e FROM Entity e
WHERE e.status = ?1 AND e.name LIKE ?2
ORDER BY e.createdAt DESC
```
