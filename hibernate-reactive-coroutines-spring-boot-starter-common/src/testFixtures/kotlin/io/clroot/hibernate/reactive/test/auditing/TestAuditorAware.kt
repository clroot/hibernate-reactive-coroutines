package io.clroot.hibernate.reactive.test.auditing

import io.clroot.hibernate.reactive.repository.auditing.ReactiveAuditorAware
import org.springframework.stereotype.Component

/**
 * AuditorAware implementation for tests.
 *
 * Tests can set the current auditor dynamically.
 */
@Component
class TestAuditorAware : ReactiveAuditorAware<String> {

    companion object {
        private val currentAuditor = ThreadLocal<String?>()

        /**
         * Sets the current auditor.
         */
        fun setCurrentAuditor(auditor: String?) {
            currentAuditor.set(auditor)
        }

        /**
         * Clears the current auditor.
         */
        fun clear() {
            currentAuditor.remove()
        }
    }

    override suspend fun getCurrentAuditor(): String? {
        return currentAuditor.get()
    }
}
