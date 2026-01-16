# Repository 인터페이스 자동 구현 설계

> GitHub Issue: [#1](https://github.com/clroot/hibernate-reactive-coroutines/issues/1)

## 결정 사항

| 항목 | 선택 | 이유 |
|------|------|------|
| 모듈 | Starter 모듈만 | Spring Bean 스캔 활용 |
| 활성화 | Auto-config + `@EnableHibernateReactiveRepositories` | 기본 자동 + 커스터마이징 지원 |
| 프록시 | Java Dynamic Proxy | 추가 의존성 없음, 인터페이스에 충분 |
| 메서드 | 표준 CRUD 7개 | 실용적인 범위 |

## 구현할 메서드

```kotlin
interface ReactiveRepository<T : Any, ID : Any> {
    suspend fun save(entity: T): T
    suspend fun findById(id: ID): T?
    suspend fun findAll(): List<T>
    suspend fun deleteById(id: ID)
    suspend fun delete(entity: T)
    suspend fun count(): Long
    suspend fun existsById(id: ID): Boolean
}
```

## 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                    Spring Boot Application                   │
├─────────────────────────────────────────────────────────────┤
│  @SpringBootApplication                                      │
│       │                                                      │
│       ▼                                                      │
│  HibernateReactiveAutoConfiguration                          │
│       │                                                      │
│       ├──▶ HibernateReactiveRepositoryRegistrar              │
│       │         │                                            │
│       │         ├──▶ 패키지 스캔 (CoroutineCrudRepository)    │
│       │         │                                            │
│       │         └──▶ HibernateReactiveRepositoryFactoryBean  │
│       │                      │                               │
│       │                      ▼                               │
│       │              Java Dynamic Proxy 생성                 │
│       │                      │                               │
│       │                      ▼                               │
│       │              SimpleHibernateReactiveRepository        │
│       │                      │                               │
│       ▼                      ▼                               │
│  ReactiveSessionProvider ◀───┘                               │
│       │                                                      │
│       ▼                                                      │
│  Mutiny.SessionFactory                                       │
└─────────────────────────────────────────────────────────────┘
```

## 핵심 컴포넌트

### 1. ReactiveRepository 인터페이스

```kotlin
interface ReactiveRepository<T : Any, ID : Any> {
    suspend fun save(entity: T): T
    suspend fun findById(id: ID): T?
    suspend fun findAll(): List<T>
    suspend fun deleteById(id: ID)
    suspend fun delete(entity: T)
    suspend fun count(): Long
    suspend fun existsById(id: ID): Boolean
}
```

- 제네릭 `T : Any`로 non-null 엔티티 보장
- 모든 메서드가 `suspend` - 코루틴 기반 비동기
- `findById`는 `T?` 반환 - Optional 대신 nullable

### 2. SimpleHibernateReactiveRepository (InvocationHandler)

```kotlin
class SimpleHibernateReactiveRepository<T : Any, ID : Any>(
    private val entityClass: Class<T>,
    private val idClass: Class<ID>,
    private val sessionProvider: ReactiveSessionProvider,
) : InvocationHandler {

    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
        // suspend 함수 감지 (Continuation 파라미터)
        val continuation = args?.lastOrNull() as? Continuation<*>

        if (continuation != null) {
            val actualArgs = args.dropLast(1)
            return invokeSuspend(method.name, actualArgs, continuation)
        }

        return invokeRegular(method, args)
    }

    private fun invokeSuspend(
        methodName: String,
        args: List<Any?>,
        continuation: Continuation<*>
    ): Any? = (suspend {
        when (methodName) {
            "save" -> save(args[0] as T)
            "findById" -> findById(args[0] as ID)
            "findAll" -> findAll()
            "deleteById" -> deleteById(args[0] as ID)
            "delete" -> delete(args[0] as T)
            "count" -> count()
            "existsById" -> existsById(args[0] as ID)
            else -> throw UnsupportedOperationException("Unknown method: $methodName")
        }
    } as Function1<Continuation<*>, Any?>).invoke(continuation)

    suspend fun save(entity: T): T = sessionProvider.write { session ->
        session.merge(entity)
    }

    suspend fun findById(id: ID): T? = sessionProvider.read { session ->
        session.find(entityClass, id)
    }

    suspend fun findAll(): List<T> = sessionProvider.read { session ->
        session.createQuery("FROM ${entityClass.simpleName}", entityClass).resultList
    }

    suspend fun deleteById(id: ID): Unit = sessionProvider.write { session ->
        session.find(entityClass, id)
            .chain { entity -> entity?.let { session.remove(it) } ?: Uni.createFrom().voidItem() }
    }

    suspend fun delete(entity: T): Unit = sessionProvider.write { session ->
        session.remove(entity)
    }

    suspend fun count(): Long = sessionProvider.read { session ->
        session.createQuery("SELECT COUNT(e) FROM ${entityClass.simpleName} e", Long::class.java)
            .singleResult
    }

    suspend fun existsById(id: ID): Boolean = findById(id) != null
}
```

- `InvocationHandler`로 Dynamic Proxy 구현
- `Continuation` 감지로 suspend 함수 지원 (runBlocking 사용 안 함)
- 기존 `ReactiveSessionProvider` 활용

### 3. HibernateReactiveRepositoryRegistrar

```kotlin
class HibernateReactiveRepositoryRegistrar : BeanDefinitionRegistryPostProcessor {

    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AssignableTypeFilter(ReactiveRepository::class.java))

        val basePackage = detectBasePackage()
        val candidates = scanner.findCandidateComponents(basePackage)

        candidates.forEach { candidate ->
            val repositoryInterface = Class.forName(candidate.beanClassName)

            // ReactiveRepository 자체는 제외
            if (repositoryInterface == ReactiveRepository::class.java) return@forEach

            val beanDefinition = BeanDefinitionBuilder
                .genericBeanDefinition(HibernateReactiveRepositoryFactoryBean::class.java)
                .addConstructorArgValue(repositoryInterface)
                .beanDefinition

            registry.registerBeanDefinition(
                repositoryInterface.simpleName.replaceFirstChar { it.lowercase() },
                beanDefinition
            )
        }
    }

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {}
}
```

### 4. HibernateReactiveRepositoryFactoryBean

```kotlin
class HibernateReactiveRepositoryFactoryBean<T : CoroutineCrudRepository<*, *>>(
    private val repositoryInterface: Class<T>,
) : FactoryBean<T> {

    @Autowired
    lateinit var sessionProvider: ReactiveSessionProvider

    override fun getObject(): T {
        val (entityClass, idClass) = extractGenericTypes(repositoryInterface)

        val handler = SimpleHibernateReactiveRepository(entityClass, idClass, sessionProvider)

        return Proxy.newProxyInstance(
            repositoryInterface.classLoader,
            arrayOf(repositoryInterface),
            handler
        ) as T
    }

    override fun getObjectType(): Class<*> = repositoryInterface

    override fun isSingleton(): Boolean = true

    private fun extractGenericTypes(repoInterface: Class<*>): Pair<Class<*>, Class<*>> {
        // ReactiveRepository<T, ID>에서 T, ID 타입 추출
        val genericInterface = repoInterface.genericInterfaces
            .filterIsInstance<ParameterizedType>()
            .first { (it.rawType as Class<*>).isAssignableFrom(ReactiveRepository::class.java) }

        val entityClass = genericInterface.actualTypeArguments[0] as Class<*>
        val idClass = genericInterface.actualTypeArguments[1] as Class<*>

        return entityClass to idClass
    }
}
```

### 5. @EnableHibernateReactiveRepositories

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Import(HibernateReactiveRepositoryRegistrar::class)
annotation class EnableHibernateReactiveRepositories(
    val basePackages: Array<String> = [],
    val basePackageClasses: Array<KClass<*>> = [],
)
```

### 6. Auto-configuration 통합

```kotlin
@AutoConfiguration
@ConditionalOnClass(CoroutineCrudRepository::class)
class HibernateReactiveAutoConfiguration {

    // 기존 Bean들...

    @Bean
    @ConditionalOnMissingBean(HibernateReactiveRepositoryRegistrar::class)
    fun hibernateReactiveRepositoryRegistrar(): HibernateReactiveRepositoryRegistrar {
        return HibernateReactiveRepositoryRegistrar()
    }
}
```

## 파일 구조

```
hibernate-reactive-coroutines-spring-boot-starter/
└── src/main/kotlin/io/clroot/hibernate/reactive/spring/boot/
    ├── autoconfigure/
    │   └── HibernateReactiveAutoConfiguration.kt  # 수정
    └── repository/
        ├── SimpleHibernateReactiveRepository.kt            # 구현
        ├── HibernateReactiveRepositoryRegistrar.kt         # 스캐너
        ├── HibernateReactiveRepositoryFactoryBean.kt       # FactoryBean
        ├── HibernateReactiveRepositoryTypeFilter.kt        # TypeFilter
        └── EnableHibernateReactiveRepositories.kt          # 어노테이션
```

## 테스트 계획

### 단위 테스트
- `SimpleHibernateReactiveRepository`: 각 메서드가 올바른 Session 메서드 호출하는지
- `HibernateReactiveRepositoryFactoryBean`: 제네릭 타입 추출 로직

### 통합 테스트
- Repository 인터페이스 정의 → Bean 자동 등록 확인
- CRUD 작업 정상 동작 확인
- 트랜잭션 롤백 확인
- `@EnableHibernateReactiveRepositories`로 패키지 지정 시 동작 확인

## 사용 예시

```kotlin
// 1. Repository 인터페이스 정의
interface UserRepository : CoroutineCrudRepository<User, Long>

// 2. 사용 (자동 주입)
@Service
class UserService(
    private val userRepository: UserRepository,
    private val tx: ReactiveTransactionExecutor,
) {
    suspend fun createUser(name: String): User = tx.transactional {
        userRepository.save(User(name = name))
    }

    suspend fun findUser(id: Long): User? = tx.readOnly {
        userRepository.findById(id)
    }
}
```
