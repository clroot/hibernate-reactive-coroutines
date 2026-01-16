package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * 쿼리 메서드 자동 생성 기능 통합 테스트.
 *
 * Spring Data 스타일의 메서드 이름 기반 쿼리 생성을 검증합니다.
 */
@SpringBootTest(classes = [TestApplication::class])
class QueryMethodIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("쿼리 메서드") {

            context("findByName - 단일 조회") {
                it("이름으로 엔티티를 조회한다") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "uniqueName123", value = 100))
                    }

                    // when
                    val found = tx.readOnly {
                        testEntityRepository.findByName("uniqueName123")
                    }

                    // then
                    found.shouldNotBeNull()
                    found.name shouldBe "uniqueName123"
                    found.value shouldBe 100
                }

                it("존재하지 않는 이름은 null을 반환한다") {
                    val found = tx.readOnly {
                        testEntityRepository.findByName("nonExistentName999")
                    }

                    found.shouldBeNull()
                }
            }

            context("findAllByValue - 리스트 조회") {
                it("값으로 여러 엔티티를 조회한다") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "listTest1", value = 777))
                        testEntityRepository.save(TestEntity(name = "listTest2", value = 777))
                        testEntityRepository.save(TestEntity(name = "listTest3", value = 888))
                    }

                    // when
                    val found = tx.readOnly {
                        testEntityRepository.findAllByValue(777)
                    }

                    // then
                    found shouldHaveSize 2
                    found.map { it.name }.toSet() shouldBe setOf("listTest1", "listTest2")
                }

                it("일치하는 값이 없으면 빈 리스트를 반환한다") {
                    val found = tx.readOnly {
                        testEntityRepository.findAllByValue(999999)
                    }

                    found.shouldBeEmpty()
                }
            }

            context("existsByName - 존재 여부") {
                it("존재하는 이름은 true를 반환한다") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "existsTest", value = 1))
                    }

                    // when
                    val exists = tx.readOnly {
                        testEntityRepository.existsByName("existsTest")
                    }

                    // then
                    exists shouldBe true
                }

                it("존재하지 않는 이름은 false를 반환한다") {
                    val exists = tx.readOnly {
                        testEntityRepository.existsByName("doesNotExist999")
                    }

                    exists shouldBe false
                }
            }

            context("countByValue - 카운트") {
                it("값으로 엔티티 개수를 센다") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "countTest1", value = 555))
                        testEntityRepository.save(TestEntity(name = "countTest2", value = 555))
                        testEntityRepository.save(TestEntity(name = "countTest3", value = 555))
                    }

                    // when
                    val count = tx.readOnly {
                        testEntityRepository.countByValue(555)
                    }

                    // then
                    count shouldBe 3
                }

                it("일치하는 값이 없으면 0을 반환한다") {
                    val count = tx.readOnly {
                        testEntityRepository.countByValue(999888)
                    }

                    count shouldBe 0
                }
            }

            context("deleteByName - 삭제") {
                it("이름으로 엔티티를 삭제한다") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "deleteTest", value = 1))
                    }

                    // 삭제 전 확인
                    val beforeDelete = tx.readOnly {
                        testEntityRepository.findByName("deleteTest")
                    }
                    beforeDelete.shouldNotBeNull()

                    // when
                    tx.transactional {
                        testEntityRepository.deleteByName("deleteTest")
                    }

                    // then
                    val afterDelete = tx.readOnly {
                        testEntityRepository.findByName("deleteTest")
                    }
                    afterDelete.shouldBeNull()
                }
            }

            context("findAllByNameContaining - LIKE 검색") {
                it("이름에 특정 문자열이 포함된 엔티티를 조회한다") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "apple_fruit", value = 1))
                        testEntityRepository.save(TestEntity(name = "pineapple_fruit", value = 2))
                        testEntityRepository.save(TestEntity(name = "banana_fruit", value = 3))
                    }

                    // when
                    val found = tx.readOnly {
                        testEntityRepository.findAllByNameContaining("apple")
                    }

                    // then
                    found shouldHaveSize 2
                    found.map { it.name }.toSet() shouldBe setOf("apple_fruit", "pineapple_fruit")
                }
            }

            context("findByNameAndValue - 복합 조건") {
                it("이름과 값 모두 일치하는 엔티티를 조회한다") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "combo", value = 111))
                        testEntityRepository.save(TestEntity(name = "combo", value = 222))
                    }

                    // when
                    val found = tx.readOnly {
                        testEntityRepository.findByNameAndValue("combo", 111)
                    }

                    // then
                    found.shouldNotBeNull()
                    found.name shouldBe "combo"
                    found.value shouldBe 111
                }

                it("이름만 일치하면 null을 반환한다") {
                    val found = tx.readOnly {
                        testEntityRepository.findByNameAndValue("combo", 999)
                    }

                    found.shouldBeNull()
                }
            }

            context("findAllByValueGreaterThan - 비교 연산") {
                it("값이 특정 값보다 큰 엔티티를 조회한다") {
                    // given - 고유한 값 범위 사용 (25000~28000)
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "gt1", value = 25000))
                        testEntityRepository.save(TestEntity(name = "gt2", value = 26000))
                        testEntityRepository.save(TestEntity(name = "gt3", value = 27000))
                    }

                    // when - 25500보다 크고 28000보다 작은 값만 조회되도록 테스트
                    val found = tx.readOnly {
                        testEntityRepository.findAllByValueGreaterThan(25500)
                    }

                    // then - 26000, 27000만 조회 (다른 테스트의 50000+ 값도 포함됨)
                    found.filter { it.value in 25501..28000 } shouldHaveSize 2
                    found.filter { it.value in 25501..28000 }.all { it.value > 25500 } shouldBe true
                }
            }

            context("findAllByValueOrderByNameDesc - 정렬") {
                it("값으로 조회하고 이름 내림차순으로 정렬한다") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "aaa_sort", value = 4444))
                        testEntityRepository.save(TestEntity(name = "ccc_sort", value = 4444))
                        testEntityRepository.save(TestEntity(name = "bbb_sort", value = 4444))
                    }

                    // when
                    val found = tx.readOnly {
                        testEntityRepository.findAllByValueOrderByNameDesc(4444)
                    }

                    // then
                    found shouldHaveSize 3
                    found.map { it.name } shouldContainExactly listOf("ccc_sort", "bbb_sort", "aaa_sort")
                }
            }
        }
    }
}
