package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.inheritance.InheritedTestEntityRepository
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Repository 상속 및 제네릭 타입 관련 테스트.
 *
 * ## 검증 항목
 * 1. 제네릭 타입 정보 보존
 * 2. 상속된 Repository 메서드 동작
 * 3. 하위 Repository에서 정의한 메서드 동작
 */
@SpringBootTest(classes = [TestApplication::class])
class RepositoryInheritanceTest : IntegrationTestBase() {

    @Autowired
    private lateinit var inheritedRepository: InheritedTestEntityRepository

    init {
        describe("제네릭 타입 정보 보존") {
            it("상속된 Repository에서 엔티티 타입이 올바르게 추론된다") {
                // given
                val entity = TestEntity(name = "generic-type-test", value = 100)

                // when - CoroutineCrudRepository의 save 메서드 사용
                val saved = inheritedRepository.save(entity)

                // then - 반환 타입이 TestEntity로 올바르게 추론됨
                saved.shouldNotBeNull()
                saved.id.shouldNotBeNull()
                saved.name shouldBe "generic-type-test"
            }

            it("제네릭 ID 타입이 올바르게 동작한다") {
                // given
                val entity = inheritedRepository.save(TestEntity(name = "id-type-test", value = 200))

                // when - Long 타입의 ID로 조회
                val found = inheritedRepository.findById(entity.id!!)

                // then
                found.shouldNotBeNull()
                found.id shouldBe entity.id
            }
        }

        describe("상속된 메서드 동작") {
            it("BaseRepository에서 정의한 findByName이 동작한다") {
                // given
                inheritedRepository.save(TestEntity(name = "inherited-method-test", value = 300))

                // when - BaseRepository에서 상속된 메서드
                val found = inheritedRepository.findByName("inherited-method-test")

                // then
                found.shouldNotBeNull()
                found.name shouldBe "inherited-method-test"
            }

            it("BaseRepository에서 정의한 existsByName이 동작한다") {
                // given
                inheritedRepository.save(TestEntity(name = "exists-test", value = 400))

                // when
                val exists = inheritedRepository.existsByName("exists-test")
                val notExists = inheritedRepository.existsByName("not-exists")

                // then
                exists shouldBe true
                notExists shouldBe false
            }
        }

        describe("하위 Repository에서 정의한 메서드") {
            it("InheritedTestEntityRepository에서 정의한 findAllByValue가 동작한다") {
                // given
                inheritedRepository.save(TestEntity(name = "value-test-1", value = 500))
                inheritedRepository.save(TestEntity(name = "value-test-2", value = 500))
                inheritedRepository.save(TestEntity(name = "value-test-3", value = 600))

                // when - 하위 Repository에서 직접 정의한 메서드
                val found = inheritedRepository.findAllByValue(500)

                // then
                found.size shouldBe 2
                found.all { it.value == 500 } shouldBe true
            }
        }

        describe("상속된 CRUD 메서드") {
            it("CoroutineCrudRepository의 count가 동작한다") {
                // given
                val initialCount = inheritedRepository.count()
                inheritedRepository.save(TestEntity(name = "count-test", value = 700))

                // when
                val newCount = inheritedRepository.count()

                // then
                newCount shouldBe initialCount + 1
            }

            it("CoroutineCrudRepository의 deleteById가 동작한다") {
                // given
                val entity = inheritedRepository.save(TestEntity(name = "delete-test", value = 800))
                val id = entity.id!!

                // when
                inheritedRepository.deleteById(id)

                // then
                inheritedRepository.findById(id).shouldBeNull()
            }
        }
    }
}
