package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(classes = [TestApplication::class])
class ReactiveRepositoryIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("CoroutineCrudRepository") {
            context("save") {
                it("엔티티를 저장하고 ID가 생성된다") {
                    val entity = TestEntity(name = "saveTest", value = 100)

                    val saved = tx.transactional {
                        testEntityRepository.save(entity)
                    }

                    saved.id.shouldNotBeNull()
                    saved.name shouldBe "saveTest"
                    saved.value shouldBe 100
                }

                it("기존 엔티티를 업데이트한다") {
                    // given
                    val entity = TestEntity(name = "updateTest", value = 50)
                    val saved = tx.transactional {
                        testEntityRepository.save(entity)
                    }

                    // when
                    saved.name = "updated"
                    saved.value = 999

                    val updated = tx.transactional {
                        testEntityRepository.save(saved)
                    }

                    // then
                    updated.id shouldBe saved.id
                    updated.name shouldBe "updated"
                    updated.value shouldBe 999
                }
            }

            context("findById") {
                it("존재하는 엔티티를 조회한다") {
                    // given
                    val entity = TestEntity(name = "findByIdTest", value = 200)
                    val saved = tx.transactional {
                        testEntityRepository.save(entity)
                    }

                    // when
                    val found = tx.readOnly {
                        testEntityRepository.findById(saved.id!!)
                    }

                    // then
                    found.shouldNotBeNull()
                    found.id shouldBe saved.id
                    found.name shouldBe "findByIdTest"
                }

                it("존재하지 않는 ID는 null을 반환한다") {
                    val found = tx.readOnly {
                        testEntityRepository.findById(99999L)
                    }

                    found.shouldBeNull()
                }
            }

            context("findAll") {
                it("모든 엔티티를 조회한다") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "all1", value = 1))
                        testEntityRepository.save(TestEntity(name = "all2", value = 2))
                        testEntityRepository.save(TestEntity(name = "all3", value = 3))
                    }

                    // when
                    val all = tx.readOnly {
                        testEntityRepository.findAll().toList()
                    }

                    // then
                    all.size shouldBe 3
                }
            }

            context("count") {
                it("엔티티 개수를 반환한다") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "count1", value = 1))
                        testEntityRepository.save(TestEntity(name = "count2", value = 2))
                    }

                    // when
                    val count = tx.readOnly {
                        testEntityRepository.count()
                    }

                    // then
                    count shouldBe 2
                }
            }

            context("existsById") {
                it("존재하는 ID는 true를 반환한다") {
                    // given
                    val saved = tx.transactional {
                        testEntityRepository.save(TestEntity(name = "exists", value = 1))
                    }

                    // when
                    val exists = tx.readOnly {
                        testEntityRepository.existsById(saved.id!!)
                    }

                    // then
                    exists shouldBe true
                }

                it("존재하지 않는 ID는 false를 반환한다") {
                    val exists = tx.readOnly {
                        testEntityRepository.existsById(99999L)
                    }

                    exists shouldBe false
                }
            }

            context("deleteById") {
                it("ID로 엔티티를 삭제한다") {
                    // given
                    val saved = tx.transactional {
                        testEntityRepository.save(TestEntity(name = "deleteById", value = 1))
                    }

                    // when
                    tx.transactional {
                        testEntityRepository.deleteById(saved.id!!)
                    }

                    // then
                    val found = tx.readOnly {
                        testEntityRepository.findById(saved.id!!)
                    }
                    found.shouldBeNull()
                }

                it("존재하지 않는 ID 삭제는 예외 없이 무시된다") {
                    tx.transactional {
                        testEntityRepository.deleteById(99999L)
                    }
                    // 예외가 발생하지 않으면 성공
                }
            }

            context("delete") {
                it("엔티티를 삭제한다") {
                    // given
                    val saved = tx.transactional {
                        testEntityRepository.save(TestEntity(name = "delete", value = 1))
                    }

                    // when
                    tx.transactional {
                        testEntityRepository.delete(saved)
                    }

                    // then
                    val found = tx.readOnly {
                        testEntityRepository.findById(saved.id!!)
                    }
                    found.shouldBeNull()
                }
            }
        }
    }
}
