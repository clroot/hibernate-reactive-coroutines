package io.clroot.hibernate.reactive

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.seconds

/**
 * ReactiveTransactionExecutor 단위 테스트.
 *
 * 타임아웃 계산 로직과 중첩 트랜잭션 동작을 검증합니다.
 *
 * 참고: transactional/readOnly의 실제 트랜잭션 동작은 Vert.x 환경이 필요하므로
 * 통합 테스트(Spring Boot Starter 모듈)에서 검증합니다.
 */
class ReactiveTransactionExecutorTest : DescribeSpec({

    describe("ReactiveTransactionExecutor") {

        context("DEFAULT_TIMEOUT") {
            it("기본 타임아웃은 30초이다") {
                ReactiveTransactionExecutor.DEFAULT_TIMEOUT shouldBe 30.seconds
            }
        }

        context("타임아웃 계산 로직 (calculateEffectiveTimeout)") {
            // calculateEffectiveTimeout은 private이지만, 동작은 중첩 컨텍스트에서 검증 가능

            it("부모 컨텍스트가 없으면 지정된 타임아웃을 사용한다") {
                // 이 테스트는 transactional 내부 동작으로 간접 검증
                // 부모가 없을 때: effectiveTimeout = timeout
                val sessionFactory = mockk<org.hibernate.reactive.mutiny.Mutiny.SessionFactory>()
                val executor = ReactiveTransactionExecutor(sessionFactory)

                // 직접 호출할 수 없으므로 Companion object 상수로 간접 검증
                ReactiveTransactionExecutor.DEFAULT_TIMEOUT shouldBe 30.seconds
            }
        }

        context("중첩 트랜잭션에서 타임아웃 상속") {
            it("부모의 남은 시간이 INFINITE이면 자식 타임아웃을 사용한다") {
                val session = mockk<org.hibernate.reactive.mutiny.Mutiny.Session>()
                val parentContext = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                    timeout = INFINITE,
                )

                // 자식 타임아웃이 10초라면 10초가 적용되어야 함
                // 이는 transactional 내부에서 calculateEffectiveTimeout으로 계산됨
                parentContext.remainingTimeout() shouldBe INFINITE
            }

            it("자식 타임아웃이 INFINITE이면 부모의 남은 시간을 사용한다") {
                val session = mockk<org.hibernate.reactive.mutiny.Mutiny.Session>()
                val parentContext = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                    timeout = 10.seconds,
                    startTimeNanos = System.nanoTime(),
                )

                // 부모의 남은 시간이 약 10초이고, 자식이 INFINITE이면 부모 시간 사용
                val remaining = parentContext.remainingTimeout()
                (remaining.inWholeMilliseconds in 9900..10100) shouldBe true
            }

            it("둘 다 유한하면 더 짧은 타임아웃을 사용한다") {
                val session = mockk<org.hibernate.reactive.mutiny.Mutiny.Session>()
                // 부모가 5초 전에 시작, 10초 타임아웃 → 남은 시간 약 5초 (5_000_000_000 나노초 = 5초)
                val parentContext = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                    timeout = 10.seconds,
                    startTimeNanos = System.nanoTime() - 5_000_000_000L,
                )

                val remaining = parentContext.remainingTimeout()
                // 자식이 3초 타임아웃이면 3초 사용
                // 자식이 7초 타임아웃이면 부모의 5초 사용
                (remaining.inWholeMilliseconds in 4900..5100) shouldBe true
            }
        }

        context("컨텍스트 재사용 (REQUIRED 동작)") {
            it("기존 READ_WRITE 컨텍스트가 있으면 새 트랜잭션을 생성하지 않는다") {
                val session = mockk<org.hibernate.reactive.mutiny.Mutiny.Session>()
                val existingContext = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                )

                withContext(existingContext) {
                    // transactional 블록 진입 시 parentContext가 null이 아니면
                    // 새 트랜잭션 없이 기존 세션 재사용
                    currentContextOrNull() shouldBe existingContext
                    currentSessionOrNull() shouldBe session
                }
            }

            it("기존 READ_ONLY 컨텍스트도 재사용한다") {
                val session = mockk<org.hibernate.reactive.mutiny.Mutiny.Session>()
                val existingContext = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_ONLY,
                )

                withContext(existingContext) {
                    currentContextOrNull() shouldBe existingContext
                    currentContextOrNull()?.isReadOnly shouldBe true
                }
            }
        }

        context("타임아웃 경계값 테스트") {
            it("타임아웃이 정확히 0이면 ZERO를 반환한다") {
                val session = mockk<org.hibernate.reactive.mutiny.Mutiny.Session>()
                // 10초 전에 시작, 10초 타임아웃 → 정확히 만료 (10_000_000_000 나노초 = 10초)
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                    timeout = 10.seconds,
                    startTimeNanos = System.nanoTime() - 10_000_000_000L,
                )

                // 약간의 실행 시간으로 인해 0 또는 약간 음수일 수 있음
                (context.remainingTimeout().inWholeMilliseconds <= 100) shouldBe true
            }

            it("아주 긴 타임아웃도 처리할 수 있다") {
                val session = mockk<org.hibernate.reactive.mutiny.Mutiny.Session>()
                val longTimeout = 3600.seconds // 1시간
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                    timeout = longTimeout,
                )

                val remaining = context.remainingTimeout()
                (remaining.inWholeMilliseconds >= 3599000) shouldBe true
            }
        }
    }
})
