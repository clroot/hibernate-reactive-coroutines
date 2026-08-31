package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.service.TransactionalTestService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.smallrye.mutiny.coroutines.awaitSuspending
import org.hibernate.reactive.mutiny.Mutiny
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext

/**
 * Spring @Transactional + Repository 통합 테스트.
 *
 * @Transactional 어노테이션과 suspend 함수를 함께 사용하여
 * 트랜잭션 세션이 자동으로 공유되는지 검증합니다.
 */
@SpringBootTest(classes = [TestApplication::class, TransactionalTestService::class])
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class SpringTransactionalIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var testService: TransactionalTestService

    @Autowired
    private lateinit var sessionFactory: Mutiny.SessionFactory

    init {
        describe("Spring @Transactional with suspend functions") {
            context("트랜잭션 컨텍스트 내에서") {
                it("Repository를 통해 엔티티를 저장할 수 있다") {
                    val result = testService.saveEntity("transactional-test", 100)

                    result.shouldNotBeNull()
                    result.id.shouldNotBeNull()
                    result.name shouldBe "transactional-test"
                }

                it("저장된 엔티티를 조회할 수 있다") {
                    // given
                    val saved = testService.saveEntity("findable", 200)

                    // when
                    val found = testService.findById(saved.id!!)

                    // then
                    found.shouldNotBeNull()
                    found.name shouldBe "findable"
                    found.value shouldBe 200
                }
            }

            context("트랜잭션 롤백") {
                it("예외 발생 시 변경이 롤백된다") {
                    var savedId: Long? = null

                    shouldThrow<RuntimeException> {
                        testService.saveAndFail("will-rollback", 999) { id ->
                            savedId = id
                        }
                    }

                    savedId.shouldNotBeNull()

                    // 롤백 확인 - 새 트랜잭션에서 조회
                    val found = testService.findById(savedId)
                    found.shouldBeNull()
                }
            }

            context("readOnly 트랜잭션") {
                it("읽기 전용 트랜잭션에서 조회가 정상 동작한다") {
                    // given
                    val saved = testService.saveEntity("readonly-test", 300)

                    // when - readOnly 트랜잭션으로 조회
                    val found = testService.findByIdReadOnly(saved.id!!)

                    // then
                    found.shouldNotBeNull()
                    found.name shouldBe "readonly-test"
                }
            }

            context("트랜잭션 커밋 시 자동 flush") {
                it("트랜잭션 커밋 후 새로운 세션에서 저장된 엔티티를 조회할 수 있다") {
                    // given - @Transactional 메서드에서 저장
                    val saved = testService.saveEntity("flush-test", 500)
                    saved.id.shouldNotBeNull()

                    // when - 트랜잭션 커밋 후 새로운 세션에서 DB 직접 조회
                    // 영속성 컨텍스트 캐시를 우회하여 실제 DB에 저장되었는지 확인
                    val foundInDb = sessionFactory.withSession { session ->
                        session.find(TestEntity::class.java, saved.id)
                    }.awaitSuspending()

                    // then - DB에 실제로 저장되어 있어야 함
                    foundInDb.shouldNotBeNull()
                    foundInDb.name shouldBe "flush-test"
                    foundInDb.value shouldBe 500
                }

                it("여러 엔티티 저장 후 트랜잭션 커밋 시 모두 DB에 반영된다") {
                    // given - 여러 엔티티를 한 트랜잭션에서 저장
                    val names = listOf("multi-flush-1", "multi-flush-2", "multi-flush-3")
                    val savedEntities = testService.saveMultipleEntities(names)

                    savedEntities shouldHaveSize 3
                    savedEntities.forEach { it.id.shouldNotBeNull() }

                    // when - 새로운 세션에서 DB 직접 조회
                    val foundInDb = sessionFactory.withSession { session ->
                        session.createQuery(
                            "FROM TestEntity WHERE name LIKE 'multi-flush-%' ORDER BY name",
                            TestEntity::class.java,
                        ).resultList
                    }.awaitSuspending()

                    // then - 모든 엔티티가 DB에 저장되어 있어야 함
                    foundInDb shouldHaveSize 3
                    foundInDb.map { it.name } shouldBe names
                }
            }

            context("여러 쓰기 작업 일괄 처리") {
                it("여러 엔티티가 한 트랜잭션에서 모두 저장된다") {
                    // given
                    val names = listOf("batch-1", "batch-2", "batch-3")

                    // when
                    val saved = testService.saveMultipleEntities(names)

                    // then
                    saved shouldHaveSize 3
                    saved.forEach { it.id.shouldNotBeNull() }
                    saved.map { it.name } shouldBe names

                    // 저장된 엔티티들 조회 확인
                    saved.forEach { entity ->
                        val found = testService.findById(entity.id!!)
                        found.shouldNotBeNull()
                        found.name shouldBe entity.name
                    }
                }

                it("예외 발생 시 모든 저장이 롤백된다") {
                    // given
                    val names = listOf("rollback-1", "rollback-2", "rollback-3")
                    var savedIds: List<Long> = emptyList()

                    // when
                    shouldThrow<RuntimeException> {
                        testService.saveMultipleAndFail(names) { ids ->
                            savedIds = ids
                        }
                    }

                    // then - 모든 ID가 할당되었지만
                    savedIds shouldHaveSize 3

                    // 롤백되어 조회되지 않아야 함
                    savedIds.forEach { id ->
                        val found = testService.findById(id)
                        found.shouldBeNull()
                    }
                }
            }
        }
    }
}
