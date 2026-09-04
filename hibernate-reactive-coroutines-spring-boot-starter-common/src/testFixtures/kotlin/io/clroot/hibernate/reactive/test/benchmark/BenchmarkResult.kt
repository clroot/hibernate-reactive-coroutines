package io.clroot.hibernate.reactive.test.benchmark

/**
 * Benchmark result metrics.
 *
 * @property name Benchmark name.
 * @property iterations Number of measured iterations.
 * @property totalTimeMs Total elapsed time in milliseconds.
 * @property avgTimeMs Average elapsed time in milliseconds.
 * @property minTimeMs Minimum elapsed time in milliseconds.
 * @property maxTimeMs Maximum elapsed time in milliseconds.
 * @property p50Ms 50th percentile (median) in milliseconds.
 * @property p95Ms 95th percentile in milliseconds.
 * @property p99Ms 99th percentile in milliseconds.
 * @property throughput Operations per second.
 */
data class BenchmarkResult(
    val name: String,
    val iterations: Int,
    val totalTimeMs: Long,
    val avgTimeMs: Double,
    val minTimeMs: Long,
    val maxTimeMs: Long,
    val p50Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
    val throughput: Double,
) {
    companion object {
        /**
         * Creates a benchmark result from measured durations.
         */
        fun fromTimings(name: String, timingsMs: List<Long>): BenchmarkResult {
            require(timingsMs.isNotEmpty()) { "Timings list cannot be empty" }

            val sorted = timingsMs.sorted()
            val total = timingsMs.sum()

            return BenchmarkResult(
                name = name,
                iterations = timingsMs.size,
                totalTimeMs = total,
                avgTimeMs = timingsMs.average(),
                minTimeMs = sorted.first(),
                maxTimeMs = sorted.last(),
                p50Ms = sorted[sorted.size / 2],
                p95Ms = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)],
                p99Ms = sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.size - 1)],
                throughput = if (total > 0) timingsMs.size * 1000.0 / total else 0.0,
            )
        }
    }

    /**
     * Prints benchmark statistics to the console.
     */
    fun printReport() {
        println(
            """
            |=== Benchmark: $name ===
            |Iterations: $iterations
            |Total: ${totalTimeMs}ms
            |Avg: ${"%.2f".format(avgTimeMs)}ms
            |Min: ${minTimeMs}ms, Max: ${maxTimeMs}ms
            |P50: ${p50Ms}ms, P95: ${p95Ms}ms, P99: ${p99Ms}ms
            |Throughput: ${"%.2f".format(throughput)} ops/sec
            |==============================
            """.trimMargin(),
        )
    }
}
