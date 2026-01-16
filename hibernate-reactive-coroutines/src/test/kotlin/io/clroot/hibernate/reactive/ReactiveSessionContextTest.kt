package io.clroot.hibernate.reactive

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.mockk
import kotlinx.coroutines.withContext
import org.hibernate.reactive.mutiny.Mutiny
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * ReactiveSessionContext 단위 테스트.
 *
 * CoroutineContext Element로서의 동작과 타임아웃 계산 로직을 검증합니다.
 */
class ReactiveSessionContextTest : DescribeSpec({

    describe("ReactiveSessionContext") {

        context("생성 및 기본 속성") {
            it("READ_WRITE 모드로 생성하면 isReadOnly가 false이다") {
                val session = mockk<Mutiny.Session>()
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                )

                context.isReadOnly shouldBe false
                context.mode shouldBe TransactionMode.READ_WRITE
                context.session shouldBeSameInstanceAs session
            }

            it("READ_ONLY 모드로 생성하면 isReadOnly가 true이다") {
                val session = mockk<Mutiny.Session>()
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_ONLY,
                )

                context.isReadOnly shouldBe true
                context.mode shouldBe TransactionMode.READ_ONLY
            }

            it("기본 타임아웃은 INFINITE이다") {
                val session = mockk<Mutiny.Session>()
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                )

                context.timeout shouldBe INFINITE
            }

            it("지정된 타임아웃이 설정된다") {
                val session = mockk<Mutiny.Session>()
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                    timeout = 30.seconds,
                )

                context.timeout shouldBe 30.seconds
            }
        }

        context("remainingTimeout 계산") {
            it("INFINITE 타임아웃이면 INFINITE를 반환한다") {
                val session = mockk<Mutiny.Session>()
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                    timeout = INFINITE,
                )

                context.remainingTimeout() shouldBe INFINITE
            }

            it("시작 직후에는 거의 전체 타임아웃이 남아있다") {
                val session = mockk<Mutiny.Session>()
                val now = System.nanoTime()
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                    timeout = 10.seconds,
                    startTimeNanos = now,
                )

                val remaining = context.remainingTimeout()
                // 약간의 실행 시간을 감안하여 9.9초 이상이면 성공
                (remaining.inWholeMilliseconds >= 9900) shouldBe true
            }

            it("시간이 경과하면 남은 타임아웃이 줄어든다") {
                val session = mockk<Mutiny.Session>()
                // 5초 전에 시작한 것으로 설정 (5_000_000_000 나노초 = 5초)
                val startTimeNanos = System.nanoTime() - 5_000_000_000L
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                    timeout = 10.seconds,
                    startTimeNanos = startTimeNanos,
                )

                val remaining = context.remainingTimeout()
                // 약 5초 남았어야 함 (오차 허용)
                (remaining.inWholeMilliseconds in 4900..5100) shouldBe true
            }

            it("타임아웃이 만료되면 ZERO를 반환한다") {
                val session = mockk<Mutiny.Session>()
                // 15초 전에 시작한 것으로 설정 (10초 타임아웃 초과, 15_000_000_000 나노초 = 15초)
                val startTimeNanos = System.nanoTime() - 15_000_000_000L
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                    timeout = 10.seconds,
                    startTimeNanos = startTimeNanos,
                )

                context.remainingTimeout() shouldBe 0.milliseconds
            }
        }

        context("CoroutineContext Element 동작") {
            it("withContext로 컨텍스트에 추가할 수 있다") {
                val session = mockk<Mutiny.Session>()
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                )

                withContext(context) {
                    val retrieved = currentContextOrNull()
                    retrieved shouldBeSameInstanceAs context
                    retrieved?.session shouldBeSameInstanceAs session
                }
            }

            it("컨텍스트가 없으면 currentContextOrNull은 null을 반환한다") {
                val result = currentContextOrNull()
                result.shouldBeNull()
            }

            it("컨텍스트가 없으면 currentSessionOrNull은 null을 반환한다") {
                val result = currentSessionOrNull()
                result.shouldBeNull()
            }

            it("currentSessionOrNull은 컨텍스트의 세션을 반환한다") {
                val session = mockk<Mutiny.Session>()
                val context = ReactiveSessionContext(
                    session = session,
                    mode = TransactionMode.READ_WRITE,
                )

                withContext(context) {
                    currentSessionOrNull() shouldBeSameInstanceAs session
                }
            }
        }

        context("중첩 컨텍스트") {
            it("내부 컨텍스트가 외부 컨텍스트를 덮어쓴다") {
                val outerSession = mockk<Mutiny.Session>()
                val innerSession = mockk<Mutiny.Session>()

                val outerContext = ReactiveSessionContext(
                    session = outerSession,
                    mode = TransactionMode.READ_WRITE,
                )
                val innerContext = ReactiveSessionContext(
                    session = innerSession,
                    mode = TransactionMode.READ_ONLY,
                )

                withContext(outerContext) {
                    currentSessionOrNull() shouldBeSameInstanceAs outerSession
                    currentContextOrNull()?.isReadOnly shouldBe false

                    withContext(innerContext) {
                        currentSessionOrNull() shouldBeSameInstanceAs innerSession
                        currentContextOrNull()?.isReadOnly shouldBe true
                    }

                    // 내부 블록을 벗어나면 다시 외부 컨텍스트
                    currentSessionOrNull() shouldBeSameInstanceAs outerSession
                    currentContextOrNull()?.isReadOnly shouldBe false
                }
            }
        }
    }

    describe("TransactionMode") {
        it("READ_ONLY와 READ_WRITE 두 가지 모드가 있다") {
            TransactionMode.entries.size shouldBe 2
            TransactionMode.entries shouldBe listOf(
                TransactionMode.READ_ONLY,
                TransactionMode.READ_WRITE,
            )
        }
    }
})
