package io.clroot.hibernate.reactive.test

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.reactive.TransactionalEventPublisher
import java.util.concurrent.CopyOnWriteArrayList

@SpringBootTest(
    classes = [
        TestApplication::class,
        TransactionalEventIntegrationTest.EventTestConfiguration::class,
    ],
)
class TransactionalEventIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var service: EventPublishingService

    @Autowired
    private lateinit var listener: TransactionalEventRecorder

    init {
        beforeEach {
            listener.events.clear()
        }

        describe("reactive transactional events") {
            it("AFTER_COMMIT listener를 커밋 후 호출한다") {
                service.publish("committed")

                listener.events shouldContainExactly listOf("committed")
            }

            it("롤백된 트랜잭션의 AFTER_COMMIT listener는 호출하지 않는다") {
                shouldThrow<ExpectedRollbackException> {
                    service.publishAndRollback("rolled-back")
                }

                listener.events shouldContainExactly emptyList()
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class EventTestConfiguration {
        @Bean
        fun eventPublishingService(
            publisher: TransactionalEventPublisher,
        ): EventPublishingService = EventPublishingService(publisher)

        @Bean
        fun transactionalEventRecorder(): TransactionalEventRecorder = TransactionalEventRecorder()
    }

    open class EventPublishingService(
        private val publisher: TransactionalEventPublisher,
    ) {
        @Transactional
        open suspend fun publish(value: String) {
            publisher.publishEvent(CommittedEvent(value)).awaitSingleOrNull()
        }

        @Transactional
        open suspend fun publishAndRollback(value: String) {
            publisher.publishEvent(CommittedEvent(value)).awaitSingleOrNull()
            throw ExpectedRollbackException()
        }
    }

    class TransactionalEventRecorder {
        val events: MutableList<String> = CopyOnWriteArrayList()

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        fun onCommitted(event: CommittedEvent) {
            events += event.value
        }
    }

    data class CommittedEvent(
        val value: String,
    )

    class ExpectedRollbackException : RuntimeException()
}
