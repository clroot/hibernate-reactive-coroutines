package io.clroot.hibernate.reactive.spring.boot.auditing

/**
 * 현재 감사자(사용자) 정보를 제공하는 인터페이스.
 *
 * Spring Data의 `AuditorAware`와 유사하지만, Kotlin Coroutines를 지원합니다.
 * `@CreatedBy`, `@LastModifiedBy` 필드에 자동으로 값을 설정할 때 사용됩니다.
 *
 * 구현 예시 (Spring Security 사용 시):
 * ```kotlin
 * @Component
 * class SecurityAuditorAware : ReactiveAuditorAware<String> {
 *     override suspend fun getCurrentAuditor(): String? {
 *         return SecurityContextHolder.getContext()
 *             ?.authentication
 *             ?.takeIf { it.isAuthenticated }
 *             ?.name
 *     }
 * }
 * ```
 *
 * @param T 감사자 타입 (일반적으로 String 또는 사용자 ID 타입)
 */
fun interface ReactiveAuditorAware<T : Any> {
    /**
     * 현재 감사자를 반환합니다.
     *
     * @return 현재 감사자 또는 null (익명 사용자인 경우)
     */
    suspend fun getCurrentAuditor(): T?
}
