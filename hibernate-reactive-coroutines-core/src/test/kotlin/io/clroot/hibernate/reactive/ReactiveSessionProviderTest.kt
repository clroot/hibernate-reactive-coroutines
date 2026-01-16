package io.clroot.hibernate.reactive

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.withContext
import org.hibernate.reactive.mutiny.Mutiny
import java.util.function.Function

/**
 * ReactiveSessionProvider 단위 테스트.
 *
 * read/write 헬퍼의 세션 재사용 및 ReadOnly 검증 로직을 테스트합니다.
 */
class ReactiveSessionProviderTest : DescribeSpec({

    describe("ReactiveSessionProvider") {

        context("read 헬퍼") {

            it("컨텍스트가 없으면 withSession으로 새 세션을 생성한다") {
                val session = mockk<Mutiny.Session>()
                val sessionFactory = mockk<Mutiny.SessionFactory>()

                every {
                    sessionFactory.withSession(any<Function<Mutiny.Session, Uni<String>>>())
                } answers {
                    val block = firstArg<Function<Mutiny.Session, Uni<String>>>()
                    block.apply(session)
                }

                val provider = ReactiveSessionProvider(sessionFactory)

                val result = provider.read { s ->
                    s shouldBe session
                    Uni.createFrom().item("result")
                }

                result shouldBe "result"
                verify(exactly = 1) {
                    sessionFactory.withSession(any<Function<Mutiny.Session, Uni<String>>>())
                }
            }

            it("컨텍스트가 있으면 기존 세션을 재사용한다") {
                val existingSession = mockk<Mutiny.Session>()
                val sessionFactory = mockk<Mutiny.SessionFactory>()
                val context = ReactiveSessionContext(
                    session = existingSession,
                    mode = TransactionMode.READ_WRITE,
                )

                val provider = ReactiveSessionProvider(sessionFactory)

                withContext(context) {
                    val result = provider.read { s ->
                        s shouldBe existingSession
                        Uni.createFrom().item("reused")
                    }

                    result shouldBe "reused"
                }

                // withSession이 호출되지 않아야 함 (세션 재사용)
                verify(exactly = 0) {
                    sessionFactory.withSession(any<Function<Mutiny.Session, Uni<String>>>())
                }
            }

            it("READ_ONLY 컨텍스트에서도 read는 정상 동작한다") {
                val existingSession = mockk<Mutiny.Session>()
                val sessionFactory = mockk<Mutiny.SessionFactory>()
                val context = ReactiveSessionContext(
                    session = existingSession,
                    mode = TransactionMode.READ_ONLY,
                )

                val provider = ReactiveSessionProvider(sessionFactory)

                withContext(context) {
                    val result = provider.read { s ->
                        s shouldBe existingSession
                        Uni.createFrom().item("read-only-ok")
                    }

                    result shouldBe "read-only-ok"
                }
            }
        }

        context("write 헬퍼") {

            it("컨텍스트가 없으면 withTransaction으로 새 트랜잭션을 생성한다") {
                val session = mockk<Mutiny.Session>()
                val sessionFactory = mockk<Mutiny.SessionFactory>()

                every {
                    sessionFactory.withTransaction(any<Function<Mutiny.Session, Uni<String>>>())
                } answers {
                    val block = firstArg<Function<Mutiny.Session, Uni<String>>>()
                    block.apply(session)
                }

                val provider = ReactiveSessionProvider(sessionFactory)

                val result = provider.write { s ->
                    s shouldBe session
                    Uni.createFrom().item("written")
                }

                result shouldBe "written"
                verify(exactly = 1) {
                    sessionFactory.withTransaction(any<Function<Mutiny.Session, Uni<String>>>())
                }
            }

            it("READ_WRITE 컨텍스트가 있으면 기존 세션을 재사용한다") {
                val existingSession = mockk<Mutiny.Session>()
                val sessionFactory = mockk<Mutiny.SessionFactory>()
                val context = ReactiveSessionContext(
                    session = existingSession,
                    mode = TransactionMode.READ_WRITE,
                )

                val provider = ReactiveSessionProvider(sessionFactory)

                withContext(context) {
                    val result = provider.write { s ->
                        s shouldBe existingSession
                        Uni.createFrom().item("reused-write")
                    }

                    result shouldBe "reused-write"
                }

                // withTransaction이 호출되지 않아야 함 (세션 재사용)
                verify(exactly = 0) {
                    sessionFactory.withTransaction(any<Function<Mutiny.Session, Uni<String>>>())
                }
            }

            it("READ_ONLY 컨텍스트에서 write를 호출하면 ReadOnlyTransactionException이 발생한다") {
                val existingSession = mockk<Mutiny.Session>()
                val sessionFactory = mockk<Mutiny.SessionFactory>()
                val context = ReactiveSessionContext(
                    session = existingSession,
                    mode = TransactionMode.READ_ONLY,
                )

                val provider = ReactiveSessionProvider(sessionFactory)

                withContext(context) {
                    val exception = shouldThrow<ReadOnlyTransactionException> {
                        provider.write { Uni.createFrom().item("should-not-reach") }
                    }

                    exception.message shouldContain "read-only transaction"
                    exception.message shouldContain "tx.transactional"
                }
            }
        }

        context("ReadOnlyTransactionException") {
            it("메시지를 포함한다") {
                val exception = ReadOnlyTransactionException("test message")
                exception.message shouldBe "test message"
            }

            it("IllegalStateException을 상속한다") {
                val exception = ReadOnlyTransactionException("test")
                (exception is IllegalStateException) shouldBe true
            }
        }
    }
})
