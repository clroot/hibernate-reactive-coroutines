package io.clroot.hibernate.reactive.test.benchmark

/**
 * 벤치마크 결과 메트릭.
 *
 * @property name 벤치마크 이름
 * @property iterations 측정 반복 횟수
 * @property totalTimeMs 총 소요 시간 (밀리초)
 * @property avgTimeMs 평균 소요 시간 (밀리초)
 * @property minTimeMs 최소 소요 시간 (밀리초)
 * @property maxTimeMs 최대 소요 시간 (밀리초)
 * @property p50Ms 50번째 백분위수 (중간값)
 * @property p95Ms 95번째 백분위수
 * @property p99Ms 99번째 백분위수
 * @property throughput 초당 처리량 (ops/sec)
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
         * 측정된 시간 목록으로부터 벤치마크 결과를 생성합니다.
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
     * 벤치마크 결과를 콘솔에 출력합니다.
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
