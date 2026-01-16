package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.recorder.QueryType
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest

/**
 * HqlRecorder 기능 테스트.
 *
 * 쿼리 캡처 및 검증 기능이 올바르게 동작하는지 확인합니다.
 */
@SpringBootTest(classes = [TestApplication::class])
class HqlRecorderTest : RecordingIntegrationTestBase() {

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("HqlRecorder") {

            context("SELECT 쿼리 기록") {

                it("findByName은 올바른 HQL을 생성한다") {
                    // when
                    tx.readOnly {
                        testEntityRepository.findByName("test")
                    }

                    // then
                    hqlRecorder.assertQueryCount(1)
                    hqlRecorder.assertLastQueryContains("WHERE e.name = :p0")
                    hqlRecorder.getLastQuery()?.queryType shouldBe QueryType.SELECT
                }

                it("findAll은 전체 조회 HQL을 생성한다") {
                    // when
                    tx.readOnly {
                        testEntityRepository.findAll().collect { /* consume flow */ }
                    }

                    // then
                    hqlRecorder.assertQueryCount(1)
                    hqlRecorder.assertLastQueryContains("FROM TestEntity")
                }

                it("findById는 단일 조회 HQL을 생성한다") {
                    // given
                    val saved = tx.transactional {
                        testEntityRepository.save(TestEntity(name = "test", value = 1))
                    }
                    hqlRecorder.clear()

                    // when
                    tx.readOnly {
                        testEntityRepository.findById(saved.id!!)
                    }

                    // then
                    // findById는 session.find를 사용하므로 HQL 쿼리가 기록되지 않음
                    hqlRecorder.queryCount() shouldBe 0
                }
            }

            context("COUNT 쿼리 기록") {

                it("count는 COUNT HQL을 생성한다") {
                    // when
                    tx.readOnly {
                        testEntityRepository.count()
                    }

                    // then
                    hqlRecorder.assertQueryCount(1)
                    hqlRecorder.assertLastQueryContains("SELECT COUNT")
                    hqlRecorder.getLastQuery()?.queryType shouldBe QueryType.COUNT
                }

                it("countByValue는 조건부 COUNT를 생성한다") {
                    // when
                    tx.readOnly {
                        testEntityRepository.countByValue(1)
                    }

                    // then
                    hqlRecorder.assertQueryCount(1)
                    hqlRecorder.assertLastQueryContains("SELECT COUNT")
                    hqlRecorder.assertLastQueryContains("WHERE e.value = :p0")
                }
            }

            context("DELETE 쿼리 기록") {

                it("deleteByName은 DELETE HQL을 생성한다") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "to-delete", value = 1))
                    }
                    hqlRecorder.clear()

                    // when
                    tx.transactional {
                        testEntityRepository.deleteByName("to-delete")
                    }

                    // then
                    hqlRecorder.assertQueryCount(1)
                    hqlRecorder.assertLastQueryContains("DELETE FROM TestEntity")
                    hqlRecorder.getLastQuery()?.queryType shouldBe QueryType.DELETE
                }
            }

            context("페이지네이션 쿼리 기록") {

                it("Page 조회 시 데이터 쿼리와 COUNT 쿼리가 실행된다") {
                    // given
                    tx.transactional {
                        repeat(10) { i ->
                            testEntityRepository.save(TestEntity(name = "page-$i", value = 1))
                        }
                    }
                    hqlRecorder.clear()

                    // when
                    tx.readOnly {
                        testEntityRepository.findAllByValue(1, PageRequest.of(0, 5))
                    }

                    // then
                    hqlRecorder.assertQueryCount(2)

                    val queries = hqlRecorder.getRecordedQueries()
                    queries[0].hql shouldBe "FROM TestEntity e WHERE e.value = :p0"
                    queries[1].queryType shouldBe QueryType.COUNT
                }
            }

            context("쿼리 시퀀스 검증") {

                it("여러 쿼리의 순서를 검증할 수 있다") {
                    // when
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "seq-1", value = 1))
                    }
                    tx.readOnly {
                        testEntityRepository.findByName("seq-1")
                    }

                    // then
                    val queries = hqlRecorder.getRecordedQueries()
                    queries.size shouldBe 1 // save는 기록되지 않고 findByName만 기록
                    hqlRecorder.assertLastQueryContains("WHERE e.name = :p0")
                }
            }

            context("타입별 쿼리 필터링") {

                it("특정 타입의 쿼리만 필터링할 수 있다") {
                    // given
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "filter-test", value = 1))
                    }
                    hqlRecorder.clear()

                    // when
                    tx.readOnly {
                        testEntityRepository.findByName("filter-test")
                        testEntityRepository.count()
                    }

                    // then
                    hqlRecorder.getQueriesByType(QueryType.SELECT).size shouldBe 1
                    hqlRecorder.getQueriesByType(QueryType.COUNT).size shouldBe 1
                    hqlRecorder.assertQueryCountByType(QueryType.DELETE, 0)
                }
            }
        }
    }
}
