package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.spring.boot.auditing.ReactiveAuditingHandler
import io.clroot.hibernate.reactive.spring.boot.repository.query.PreparedQueryMethod
import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import org.springframework.beans.factory.FactoryBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.GenericTypeResolver
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.lang.reflect.Proxy

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
class HibernateReactiveRepositoryFactoryBean<T : CoroutineCrudRepository<*, *>>(
    private val repositoryInterface: Class<T>,
) : FactoryBean<T> {

    @Autowired
    lateinit var sessionProvider: TransactionalAwareSessionProvider

    @Autowired
    lateinit var transactionExecutor: ReactiveTransactionExecutor

    @Autowired(required = false)
    var auditingHandler: ReactiveAuditingHandler<*>? = null

    @Suppress("UNCHECKED_CAST")
    override fun getObject(): T {
        val (entityClass, idClass) = extractGenericTypes(repositoryInterface)

        // 커스텀 쿼리 메서드 파싱
        val queryMethods = parseQueryMethods(entityClass)

        val handler = SimpleHibernateReactiveRepository(
            entityClass = entityClass as Class<Any>,
            idClass = idClass as Class<Any>,
            sessionProvider = sessionProvider,
            transactionExecutor = transactionExecutor,
            queryMethods = queryMethods,
            auditingHandler = auditingHandler,
        )

        return Proxy.newProxyInstance(
            repositoryInterface.classLoader,
            arrayOf(repositoryInterface),
            handler,
        ) as T
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
     */
    private fun parseQueryMethods(entityClass: Class<*>): Map<String, PreparedQueryMethod> {
        val parser = QueryMethodParser(entityClass)

        return repositoryInterface.methods
            .filter { parser.isCustomQueryMethod(it) }
            .associate { method ->
                parser.createMethodKey(method) to parser.parse(method)
            }
    }
}
