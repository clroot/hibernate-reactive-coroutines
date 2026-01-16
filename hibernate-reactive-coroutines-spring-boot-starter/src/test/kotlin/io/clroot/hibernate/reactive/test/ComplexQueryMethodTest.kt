package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * 복잡한 쿼리 메서드 테스트.
 *
 * 다양한 조건 조합과 Edge Case를 검증합니다.
 */
@SpringBootTest(classes = [TestApplication::class])
class ComplexQueryMethodTest : IntegrationTestBase() {

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("복잡한 쿼리 메서드") {

            context("LIKE 패턴 Edge Cases") {

                it("빈 문자열로 Containing 검색") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "empty-test-1", value = 1))
                        testEntityRepository.save(TestEntity(name = "empty-test-2", value = 2))
                    }

                    // when - 빈 문자열은 모든 것에 매칭
                    val found = tx.readOnly {
                        testEntityRepository.findAllByNameContaining("")
                    }

                    // then - 모든 엔티티가 매칭됨
                    found shouldHaveSize 2
                }

                it("특수 문자가 포함된 Containing 검색") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "specialchar_underscore_test", value = 1))
                        testEntityRepository.save(TestEntity(name = "specialchar|pipe|test", value = 2))
                        testEntityRepository.save(TestEntity(name = "specialchar~tilde~test", value = 3))
                    }

                    // when
                    val withUnderscore = tx.readOnly {
                        testEntityRepository.findAllByNameContaining("_underscore_")
                    }
                    val withPipe = tx.readOnly {
                        testEntityRepository.findAllByNameContaining("|pipe|")
                    }
                    val withTilde = tx.readOnly {
                        testEntityRepository.findAllByNameContaining("~tilde~")
                    }

                    // then
                    withUnderscore shouldHaveSize 1
                    withPipe shouldHaveSize 1
                    withTilde shouldHaveSize 1
                }

                it("대소문자 구분 Containing 검색") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "CamelCase", value = 1))
                        testEntityRepository.save(TestEntity(name = "camelcase", value = 2))
                        testEntityRepository.save(TestEntity(name = "CAMELCASE", value = 3))
                    }

                    // when - 기본적으로 대소문자 구분
                    val found = tx.readOnly {
                        testEntityRepository.findAllByNameContaining("Camel")
                    }

                    // then
                    found shouldHaveSize 1
                    found[0].name shouldBe "CamelCase"
                }
            }

            context("비교 연산자 Edge Cases") {

                it("GreaterThan 경계값 테스트") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "boundary-99", value = 99))
                        testEntityRepository.save(TestEntity(name = "boundary-100", value = 100))
                        testEntityRepository.save(TestEntity(name = "boundary-101", value = 101))
                    }

                    // when - 100보다 큰 값
                    val found = tx.readOnly {
                        testEntityRepository.findAllByValueGreaterThan(100)
                    }

                    // then - 101만 포함 (100은 미포함)
                    val filtered = found.filter { it.name.startsWith("boundary-") }
                    filtered shouldHaveSize 1
                    filtered[0].value shouldBe 101
                }

                it("음수 값 비교") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "negative-1", value = -10))
                        testEntityRepository.save(TestEntity(name = "negative-2", value = -5))
                        testEntityRepository.save(TestEntity(name = "negative-3", value = 0))
                    }

                    // when
                    val found = tx.readOnly {
                        testEntityRepository.findAllByValueGreaterThan(-8)
                    }

                    // then
                    val filtered = found.filter { it.name.startsWith("negative-") }
                    filtered shouldHaveSize 2
                    filtered.map { it.value }.toSet() shouldBe setOf(-5, 0)
                }
            }

            context("복합 조건 (AND)") {

                it("이름과 값이 모두 일치하는 경우만 조회") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "combo-match", value = 100))
                        testEntityRepository.save(TestEntity(name = "combo-match", value = 200))
                        testEntityRepository.save(TestEntity(name = "combo-other", value = 100))
                    }

                    // when
                    val found = tx.readOnly {
                        testEntityRepository.findByNameAndValue("combo-match", 100)
                    }

                    // then
                    found.shouldNotBeNull()
                    found.name shouldBe "combo-match"
                    found.value shouldBe 100
                }

                it("둘 중 하나만 일치하면 null 반환") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "partial-match", value = 500))
                    }

                    // when - 이름은 일치하지만 값이 다름
                    val found1 = tx.readOnly {
                        testEntityRepository.findByNameAndValue("partial-match", 999)
                    }

                    // when - 값은 일치하지만 이름이 다름
                    val found2 = tx.readOnly {
                        testEntityRepository.findByNameAndValue("wrong-name", 500)
                    }

                    // then
                    found1.shouldBeNull()
                    found2.shouldBeNull()
                }
            }

            context("정렬과 조건 조합") {

                it("조건 필터링 후 정렬") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "zebra", value = 7777))
                        testEntityRepository.save(TestEntity(name = "apple", value = 7777))
                        testEntityRepository.save(TestEntity(name = "mango", value = 7777))
                        testEntityRepository.save(TestEntity(name = "banana", value = 8888)) // 다른 값
                    }

                    // when
                    val found = tx.readOnly {
                        testEntityRepository.findAllByValueOrderByNameDesc(7777)
                    }

                    // then - 7777만 조회되고 이름 내림차순
                    found shouldHaveSize 3
                    found.map { it.name } shouldBe listOf("zebra", "mango", "apple")
                }
            }

            context("존재 여부 (exists)") {

                it("여러 개 존재해도 true 반환") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "duplicate-name", value = 1))
                        testEntityRepository.save(TestEntity(name = "duplicate-name", value = 2))
                        testEntityRepository.save(TestEntity(name = "duplicate-name", value = 3))
                    }

                    // when
                    val exists = tx.readOnly {
                        testEntityRepository.existsByName("duplicate-name")
                    }

                    // then
                    exists shouldBe true
                }
            }

            context("카운트 (count)") {

                it("0개일 때 0 반환") {
                    val count = tx.readOnly {
                        testEntityRepository.countByValue(999999999)
                    }

                    count shouldBe 0
                }

                it("대량 데이터 카운트") {
                    // given
                    tx.transactional {
                        repeat(50) { i ->
                            testEntityRepository.save(TestEntity(name = "mass-count-$i", value = 12345))
                        }
                    }

                    // when
                    val count = tx.readOnly {
                        testEntityRepository.countByValue(12345)
                    }

                    // then
                    count shouldBe 50
                }
            }

            context("삭제 (delete)") {

                it("일치하는 항목이 없어도 예외 없이 완료") {
                    // when & then - 예외 없이 완료되어야 함
                    tx.transactional {
                        testEntityRepository.deleteByName("non-existent-for-delete")
                    }
                }

                it("동일 이름 여러 개 삭제") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "multi-delete", value = 1))
                        testEntityRepository.save(TestEntity(name = "multi-delete", value = 2))
                        testEntityRepository.save(TestEntity(name = "keep-this", value = 3))
                    }

                    // when
                    tx.transactional {
                        testEntityRepository.deleteByName("multi-delete")
                    }

                    // then
                    val deleted = tx.readOnly {
                        testEntityRepository.findAllByNameContaining("multi-delete")
                    }
                    val kept = tx.readOnly {
                        testEntityRepository.findByName("keep-this")
                    }

                    deleted.shouldBeEmpty()
                    kept.shouldNotBeNull()
                }
            }
        }
    }
}
