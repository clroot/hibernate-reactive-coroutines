package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.lang.reflect.Proxy
import kotlin.coroutines.EmptyCoroutineContext

class SimpleHibernateReactiveRepositoryTest : DescribeSpec({

    val sessionProvider = mockk<TransactionalAwareSessionProvider>()
    val transactionExecutor = mockk<ReactiveTransactionExecutor>()

    fun createProxy(): TestRepository {
        val handler = SimpleHibernateReactiveRepository(
            entityClass = TestEntity::class.java,
            idClass = Long::class.java,
            sessionProvider = sessionProvider,
            transactionExecutor = transactionExecutor,
        )
        return Proxy.newProxyInstance(
            TestRepository::class.java.classLoader,
            arrayOf(TestRepository::class.java),
            handler,
        ) as TestRepository
    }

    describe("SimpleHibernateReactiveRepository") {

        context("Object 메서드 처리") {

            it("toString은 엔티티명Repository(proxy) 형식을 반환한다") {
                val proxy = createProxy()

                proxy.toString() shouldContain "TestEntity"
                proxy.toString() shouldContain "Repository"
                proxy.toString() shouldContain "proxy"
            }

            it("hashCode는 일관된 값을 반환한다") {
                val proxy = createProxy()

                val hash1 = proxy.hashCode()
                val hash2 = proxy.hashCode()

                hash1 shouldBe hash2
            }

            it("equals는 자기 자신과 비교할 때 true를 반환한다") {
                val proxy = createProxy()

                (proxy == proxy) shouldBe true
            }

            it("equals는 다른 프록시와 비교할 때 false를 반환한다") {
                val proxy1 = createProxy()
                val proxy2 = createProxy()

                (proxy1 == proxy2) shouldBe false
            }

            it("equals는 null과 비교할 때 false를 반환한다") {
                val proxy = createProxy()
                proxy shouldNotBe null
            }

            it("서로 다른 프록시는 서로 다른 hashCode를 가진다") {
                val proxy1 = createProxy()
                val proxy2 = createProxy()

                proxy1.hashCode() shouldNotBe proxy2.hashCode()
            }
        }

        context("에러 메시지 개선") {

            it("알 수 없는 메서드 호출 시 유사한 메서드를 추천한다") {
                val handler = SimpleHibernateReactiveRepository(
                    entityClass = TestEntity::class.java,
                    idClass = Long::class.java,
                    sessionProvider = sessionProvider,
                    transactionExecutor = transactionExecutor,
                )

                // findByld (오타: Id -> ld) 호출 시뮬레이션
                val method = RepositoryWithCustomMethod::class.java.getMethod(
                    "findByld",
                    Long::class.java,
                    kotlin.coroutines.Continuation::class.java,
                )
                mockk<kotlin.coroutines.Continuation<Any?>> {
                    every { context } returns EmptyCoroutineContext
                }

                // invoke는 COROUTINE_SUSPENDED를 반환하고, 예외는 continuation으로 전달됨
                var capturedError: Throwable? = null
                val capturingContinuation = object : kotlin.coroutines.Continuation<Any?> {
                    override val context = EmptyCoroutineContext
                    override fun resumeWith(result: Result<Any?>) {
                        capturedError = result.exceptionOrNull()
                    }
                }

                handler.invoke(mockk(), method, arrayOf(1L, capturingContinuation))

                // 비동기 작업 완료 대기
                delay(100)

                capturedError shouldNotBe null
                capturedError!!.message shouldContain "Unknown method: findByld"
                capturedError!!.message shouldContain "Did you mean"
                capturedError!!.message shouldContain "findById"
            }

            it("save 오타 시 save를 추천한다") {
                val handler = SimpleHibernateReactiveRepository(
                    entityClass = TestEntity::class.java,
                    idClass = Long::class.java,
                    sessionProvider = sessionProvider,
                    transactionExecutor = transactionExecutor,
                )

                // sav (오타) 호출 시뮬레이션
                val method = RepositoryWithCustomMethod::class.java.getMethod(
                    "sav",
                    TestEntity::class.java,
                    kotlin.coroutines.Continuation::class.java,
                )

                var capturedError: Throwable? = null
                val capturingContinuation = object : kotlin.coroutines.Continuation<Any?> {
                    override val context = EmptyCoroutineContext
                    override fun resumeWith(result: Result<Any?>) {
                        capturedError = result.exceptionOrNull()
                    }
                }

                handler.invoke(mockk(), method, arrayOf(TestEntity(), capturingContinuation))

                // 비동기 작업 완료 대기
                delay(100)

                capturedError shouldNotBe null
                capturedError!!.message shouldContain "Unknown method: sav"
                capturedError!!.message shouldContain "save"
            }
        }
    }
}) {
    companion object {
        interface TestRepository : CoroutineCrudRepository<TestEntity, Long>
        class TestEntity

        // 에러 메시지 테스트용 인터페이스 (오타가 포함된 메서드명)
        @Suppress("unused")
        interface RepositoryWithCustomMethod : CoroutineCrudRepository<TestEntity, Long> {
            @Suppress("SpellCheckingInspection")
            suspend fun findByld(id: Long): TestEntity?  // findById 오타
            suspend fun sav(entity: TestEntity): TestEntity  // save 오타
        }
    }
}
