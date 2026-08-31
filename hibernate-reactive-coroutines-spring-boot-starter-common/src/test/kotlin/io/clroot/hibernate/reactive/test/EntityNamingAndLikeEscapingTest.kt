package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.test.entity.RenamedEntity
import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.RenamedEntityRepository
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * 엔티티 이름 해석과 LIKE 와일드카드 이스케이프를 실제 DB에 대해 검증합니다.
 */
@SpringBootTest
class EntityNamingAndLikeEscapingTest : IntegrationTestBase() {

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    @Autowired
    private lateinit var renamedEntityRepository: RenamedEntityRepository

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    init {
        describe("@Entity(name = ...)로 이름을 바꾼 엔티티") {

            it("기본 CRUD가 동작한다") {
                val saved = tx.transactional {
                    renamedEntityRepository.save(RenamedEntity(name = "renamed"))
                }

                tx.readOnly {
                    renamedEntityRepository.findById(saved.id!!).shouldNotBeNull().name shouldBe "renamed"
                    renamedEntityRepository.count() shouldBe 1L
                }
            }

            it("파생 쿼리가 동작한다") {
                tx.transactional {
                    renamedEntityRepository.save(RenamedEntity(name = "target"))
                    renamedEntityRepository.save(RenamedEntity(name = "other"))
                }

                tx.readOnly {
                    renamedEntityRepository.findByName("target").shouldNotBeNull()
                }

                val deleted = tx.transactional { renamedEntityRepository.deleteByName("target") }

                deleted shouldBe 1L
                tx.readOnly { renamedEntityRepository.count() } shouldBe 1L
            }
        }

        describe("LIKE 와일드카드 이스케이프") {

            it("Containing에 %를 넘겨도 전체 행이 매칭되지 않는다") {
                tx.transactional {
                    testEntityRepository.save(TestEntity(name = "plain", value = 1))
                    testEntityRepository.save(TestEntity(name = "100% cotton", value = 2))
                }

                val matched = tx.readOnly { testEntityRepository.findAllByNameContaining("%") }

                matched.map { it.name } shouldContainExactlyInAnyOrder listOf("100% cotton")
            }

            it("Containing의 _는 임의의 한 글자가 아니라 밑줄 자체로 매칭된다") {
                tx.transactional {
                    testEntityRepository.save(TestEntity(name = "a_b", value = 1))
                    testEntityRepository.save(TestEntity(name = "axb", value = 2))
                }

                val matched = tx.readOnly { testEntityRepository.findAllByNameContaining("a_b") }

                matched.map { it.name } shouldContainExactlyInAnyOrder listOf("a_b")
            }
        }
    }
}
