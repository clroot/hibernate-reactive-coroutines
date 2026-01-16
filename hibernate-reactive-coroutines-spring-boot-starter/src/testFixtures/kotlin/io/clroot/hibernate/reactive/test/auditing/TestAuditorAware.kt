package io.clroot.hibernate.reactive.test.auditing

import io.clroot.hibernate.reactive.spring.boot.auditing.ReactiveAuditorAware
import org.springframework.stereotype.Component

/**
 * 테스트용 AuditorAware 구현체.
 *
 * 테스트에서 currentAuditor를 동적으로 설정할 수 있습니다.
 */
@Component
class TestAuditorAware : ReactiveAuditorAware<String> {

    companion object {
        private val currentAuditor = ThreadLocal<String?>()

        /**
         * 현재 감사자를 설정합니다.
         */
        fun setCurrentAuditor(auditor: String?) {
            currentAuditor.set(auditor)
        }

        /**
         * 현재 감사자를 초기화합니다.
         */
        fun clear() {
            currentAuditor.remove()
        }
    }

    override suspend fun getCurrentAuditor(): String? {
        return currentAuditor.get()
    }
}
