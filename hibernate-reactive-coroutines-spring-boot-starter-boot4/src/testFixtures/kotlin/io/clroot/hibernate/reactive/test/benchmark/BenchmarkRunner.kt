package io.clroot.hibernate.reactive.test.benchmark

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Collections

/**
 * 벤치마크 실행기.
 *
 * 워밍업과 측정을 수행하여 [BenchmarkResult]를 반환합니다.
 *
 * @property warmupIterations 워밍업 반복 횟수
 * @property measureIterations 측정 반복 횟수
 */
class BenchmarkRunner(
    private val warmupIterations: Int = 10,
    private val measureIterations: Int = 100,
) {
    /**
     * 단일 작업을 벤치마크합니다.
     *
     * @param name 벤치마크 이름
     * @param setup 각 반복 전 실행할 설정 작업 (선택)
     * @param teardown 각 반복 후 실행할 정리 작업 (선택)
     * @param block 벤치마크할 작업
     * @return 벤치마크 결과
     */
    suspend fun <T> benchmark(
        name: String,
        setup: suspend () -> Unit = {},
        teardown: suspend () -> Unit = {},
        block: suspend () -> T,
    ): BenchmarkResult {
        // Warmup
        repeat(warmupIterations) {
            setup()
            block()
            teardown()
        }

        // Measure
        val timings = mutableListOf<Long>()
        repeat(measureIterations) {
            setup()
            val start = System.currentTimeMillis()
            block()
            timings.add(System.currentTimeMillis() - start)
            teardown()
        }

        return BenchmarkResult.fromTimings(name, timings)
    }

    /**
     * 동시성 작업을 벤치마크합니다.
     *
     * @param name 벤치마크 이름
     * @param concurrency 동시 실행 코루틴 수
     * @param iterationsPerCoroutine 코루틴당 반복 횟수
     * @param block 벤치마크할 작업
     * @return 벤치마크 결과
     */
    suspend fun <T> benchmarkConcurrent(
        name: String,
        concurrency: Int,
        iterationsPerCoroutine: Int = 10,
        block: suspend () -> T,
    ): BenchmarkResult {
        // Warmup
        coroutineScope {
            repeat(concurrency) {
                async { block() }
            }.let { /* await all warmup */ }
        }

        // Measure
        val timings = Collections.synchronizedList(mutableListOf<Long>())

        coroutineScope {
            (1..concurrency).map {
                async {
                    repeat(iterationsPerCoroutine) {
                        val start = System.currentTimeMillis()
                        block()
                        timings.add(System.currentTimeMillis() - start)
                    }
                }
            }.awaitAll()
        }

        return BenchmarkResult.fromTimings(name, timings)
    }
}
