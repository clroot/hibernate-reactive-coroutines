package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.repository.runtime.RepositoryFactory
import io.clroot.hibernate.reactive.spring.boot.auditing.ReactiveAuditingHandler
import io.clroot.hibernate.reactive.spring.boot.repository.query.PreparedQueryMethod
import io.clroot.hibernate.reactive.spring.boot.repository.query.toRuntimeQuery
import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import org.springframework.beans.factory.FactoryBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.GenericTypeResolver
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.lang.reflect.Method

/**
 * CoroutineCrudRepository 프록시를 생성하는 FactoryBean.
 *
 * Spring이 이 FactoryBean을 통해 Repository 인터페이스의 프록시 구현체를 생성합니다.
 * 애플리케이션 시작 시 [QueryMethodParser]를 사용하여 커스텀 쿼리 메서드를 파싱하고
 * [PreparedQueryMethod]로 캐싱합니다.
 *
 * @param T Repository 인터페이스 타입
 * @param repositoryInterface Repository 인터페이스 클래스
 */
public class HibernateReactiveRepositoryFactoryBean<T : CoroutineCrudRepository<*, *>>(
    private val repositoryInterface: Class<T>,
) : FactoryBean<T> {

    @Autowired
    public lateinit var sessionProvider: TransactionalAwareSessionProvider

    @Autowired
    public lateinit var transactionExecutor: ReactiveTransactionExecutor

    @Autowired(required = false)
    public var auditingHandler: ReactiveAuditingHandler<*>? = null

    @Suppress("UNCHECKED_CAST")
    override fun getObject(): T {
        val (entityClass, idClass) = extractGenericTypes(repositoryInterface)
        val entityName = resolveEntityName(entityClass)

        // 커스텀 쿼리 메서드 파싱
        val queryMethods = parseQueryMethods(entityClass, entityName)

        return RepositoryFactory(
            sessionOperations = sessionProvider,
            metamodel = sessionProvider.metamodel,
            runtimeAdapter = SpringRepositoryRuntimeAdapter,
            entityLifecycle = SpringRepositoryEntityLifecycle(auditingHandler),
        ).create(
            repositoryInterface = repositoryInterface,
            entityClass = entityClass as Class<Any>,
            idClass = idClass as Class<Any>,
            entityName = entityName,
            queryMethods = queryMethods.mapValues { (_, prepared) -> prepared.toRuntimeQuery() },
        )
    }

    override fun getObjectType(): Class<*> = repositoryInterface

    override fun isSingleton(): Boolean = true

    /**
     * Repository 인터페이스에서 엔티티 타입과 ID 타입을 추출합니다.
     *
     * Spring의 GenericTypeResolver를 사용하여 복잡한 제네릭 상속 구조도 처리합니다.
     */
    private fun extractGenericTypes(repoInterface: Class<*>): Pair<Class<*>, Class<*>> {
        val types = GenericTypeResolver.resolveTypeArguments(
            repoInterface,
            CoroutineCrudRepository::class.java,
        )

        if (types == null || types.size < 2) {
            throw IllegalArgumentException(
                "Cannot extract generic types from ${repoInterface.name}. " +
                        "Make sure it extends CoroutineCrudRepository<T, ID>",
            )
        }

        return types[0] to types[1]
    }

    /**
     * Repository 인터페이스의 커스텀 쿼리 메서드들을 파싱합니다.
     *
     * 런타임 라우팅은 메서드명과 인자 개수만으로 이루어지므로, 실행 불가능한 선언은
     * 여기서 모두 거부합니다.
     */
    /**
     * HQL에 사용할 엔티티 이름을 JPA 메타모델에서 조회합니다.
     *
     * `@Entity(name = "...")`로 이름을 바꿨거나 서로 다른 패키지에 같은 단순 이름의 엔티티가
     * 있으면 클래스의 단순 이름은 올바른 HQL을 만들지 못합니다.
     */
    private fun resolveEntityName(entityClass: Class<*>): String =
        try {
            sessionProvider.metamodel.entity(entityClass).name
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException(
                "${entityClass.name} is not a managed entity. " +
                        "Repository '${repositoryInterface.name}' requires its entity type to be registered " +
                        "with the Hibernate Reactive session factory.",
                e,
            )
        }

    private fun parseQueryMethods(
        entityClass: Class<*>,
        entityName: String,
    ): Map<String, PreparedQueryMethod> {
        val parser = QueryMethodParser(entityClass, entityName)
        val declaredMethods = repositoryInterface.methods
            .filter { parser.isDeclaredRepositoryMethod(it) }

        rejectNonSuspendMethods(parser, declaredMethods)

        val queryMethods = declaredMethods.filter { parser.isSuspendMethod(it) }
        rejectAmbiguousOverloads(parser, queryMethods)

        return queryMethods.associate { method ->
            parser.createMethodKey(method) to parser.parse(method)
        }
    }

    private fun rejectNonSuspendMethods(parser: QueryMethodParser, methods: List<Method>) {
        val offender = methods.firstOrNull { !parser.isSuspendMethod(it) } ?: return

        throw IllegalStateException(
            "Repository method '${repositoryInterface.name}.${offender.name}' must be a suspend function. " +
                    "Non-suspend query methods (including Flow-returning ones) are not supported; " +
                    "declare it as 'suspend fun ${offender.name}(...): List<T>' instead.",
        )
    }

    /**
     * 런타임 조회 키가 `이름#인자수`이므로, 같은 이름과 인자 개수를 가진 오버로드는
     * 어느 쪽이 선택될지 결정되지 않습니다.
     */
    private fun rejectAmbiguousOverloads(parser: QueryMethodParser, methods: List<Method>) {
        methods
            .groupBy { parser.createMethodKey(it) }
            .forEach { (key, overloads) ->
                val distinctSignatures = overloads.distinctBy { it.parameterTypes.toList() }
                if (distinctSignatures.size > 1) {
                    val signatures = distinctSignatures.joinToString(", ") { method ->
                        method.parameterTypes.dropLast(1).joinToString(", ") { it.simpleName }
                    }
                    throw IllegalStateException(
                        "Repository '${repositoryInterface.name}' declares ambiguous overloads for '$key': " +
                                "[$signatures]. Query methods are resolved by name and argument count, " +
                                "so overloads with the same argument count are not supported.",
                    )
                }
            }
    }
}
