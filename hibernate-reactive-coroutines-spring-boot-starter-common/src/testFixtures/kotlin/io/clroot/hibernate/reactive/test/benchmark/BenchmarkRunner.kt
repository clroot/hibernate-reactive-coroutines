package io.clroot.hibernate.reactive.test.benchmark

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Collections

/**
 * Benchmark runner.
 *
 * Runs warmup and measured iterations and returns a [BenchmarkResult].
 *
 * @property warmupIterations Number of warmup iterations.
 * @property measureIterations Number of measured iterations.
 */
class BenchmarkRunner(
    private val warmupIterations: Int = 10,
    private val measureIterations: Int = 100,
) {
    /**
     * Benchmarks a single operation.
     *
     * @param name Benchmark name.
     * @param setup Optional setup run before each iteration.
     * @param teardown Optional cleanup run after each iteration.
     * @param block Operation to benchmark.
     * @return Benchmark result.
     */
    suspend fun <T> benchmark(
        name: String,
        setup: suspend () -> Unit = {},
        teardown: suspend () -> Unit = {},
        block: suspend () -> T,
    ): BenchmarkResult {
        repeat(warmupIterations) {
            setup()
            block()
            teardown()
        }

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
     * Benchmarks concurrent operations.
     *
     * @param name Benchmark name.
     * @param concurrency Number of concurrently running coroutines.
     * @param iterationsPerCoroutine Number of iterations per coroutine.
     * @param block Operation to benchmark.
     * @return Benchmark result.
     */
    suspend fun <T> benchmarkConcurrent(
        name: String,
        concurrency: Int,
        iterationsPerCoroutine: Int = 10,
        block: suspend () -> T,
    ): BenchmarkResult {
        coroutineScope {
            repeat(concurrency) {
                async { block() }
            }.let { }
        }

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
