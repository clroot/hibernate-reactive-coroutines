package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.spring.boot.autoconfigure.HibernateReactiveAutoConfiguration
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * 통합 테스트 베이스 클래스.
 *
 * PostgreSQL TestContainer와 Spring 컨텍스트를 자동으로 설정합니다.
 *
 * 사용 예:
 * ```kotlin
 * @SpringBootTest
 * class MyIntegrationTest : IntegrationTestBase() {
 *     @Autowired
 *     private lateinit var tx: ReactiveTransactionExecutor
 *
 *     init {
 *         describe("MyFeature") {
 *             it("should work") {
 *                 // test code
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * Note: Hibernate Reactive는 Spring의 @Transactional을 사용하지 않습니다.
 * 트랜잭션 관리는 ReactiveTransactionExecutor를 통해 수행됩니다.
 */
@ActiveProfiles("test")
@Import(HibernateReactiveAutoConfiguration::class)
abstract class IntegrationTestBase : DescribeSpec() {
    init {
        extension(DatabaseTestExtension())
        extension(SpringExtension())
    }
}
