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
 * Tests HqlRecorder.
 *
 * Verifies query capture and assertion behavior.
 */
@SpringBootTest(classes = [TestApplication::class])
class HqlRecorderTest : RecordingIntegrationTestBase() {

    @Autowired
    private lateinit var testEntityRepository: TestEntityRepository

    @Autowired
    private lateinit var tx: ReactiveTransactionExecutor

    init {
        describe("HqlRecorder") {

            context("SELECT query recording") {

                it("generates the expected HQL for findByName") {
                    tx.readOnly {
                        testEntityRepository.findByName("test")
                    }

                    hqlRecorder.assertQueryCount(1)
                    hqlRecorder.assertLastQueryContains("WHERE e.name = :p0")
                    hqlRecorder.getLastQuery()?.queryType shouldBe QueryType.SELECT
                }

                it("generates HQL for findAll") {
                    tx.readOnly {
                        testEntityRepository.findAll().collect { }
                    }

                    hqlRecorder.assertQueryCount(1)
                    hqlRecorder.assertLastQueryContains("FROM TestEntity")
                }

                it("does not record HQL for findById") {
                    val saved = tx.transactional {
                        testEntityRepository.save(TestEntity(name = "test", value = 1))
                    }
                    hqlRecorder.clear()

                    tx.readOnly {
                        testEntityRepository.findById(saved.id!!)
                    }

                    // findById uses session.find rather than an HQL query.
                    hqlRecorder.queryCount() shouldBe 0
                }
            }

            context("COUNT query recording") {

                it("generates COUNT HQL for count") {
                    tx.readOnly {
                        testEntityRepository.count()
                    }

                    hqlRecorder.assertQueryCount(1)
                    hqlRecorder.assertLastQueryContains("SELECT COUNT")
                    hqlRecorder.getLastQuery()?.queryType shouldBe QueryType.COUNT
                }

                it("generates conditional COUNT HQL for countByValue") {
                    tx.readOnly {
                        testEntityRepository.countByValue(1)
                    }

                    hqlRecorder.assertQueryCount(1)
                    hqlRecorder.assertLastQueryContains("SELECT COUNT")
                    hqlRecorder.assertLastQueryContains("WHERE e.value = :p0")
                }
            }

            context("DELETE query recording") {

                it("loads the target before deleteByName removes it") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "to-delete", value = 1))
                    }
                    hqlRecorder.clear()

                    tx.transactional {
                        testEntityRepository.deleteByName("to-delete")
                    }

                    // Load and remove entities so cascades and @Version semantics are preserved.
                    hqlRecorder.assertQueryCount(1)
                    hqlRecorder.assertLastQueryContains("FROM TestEntity")
                    hqlRecorder.getLastQuery()?.queryType shouldBe QueryType.SELECT
                }
            }

            context("pagination query recording") {

                it("executes data and COUNT queries when retrieving a Page") {
                    tx.transactional {
                        repeat(10) { i ->
                            testEntityRepository.save(TestEntity(name = "page-$i", value = 1))
                        }
                    }
                    hqlRecorder.clear()

                    tx.readOnly {
                        testEntityRepository.findAllByValue(1, PageRequest.of(0, 5))
                    }

                    hqlRecorder.assertQueryCount(2)

                    val queries = hqlRecorder.getRecordedQueries()
                    queries[0].hql shouldBe "FROM TestEntity e WHERE e.value = :p0"
                    queries[1].queryType shouldBe QueryType.COUNT
                }
            }

            context("query sequence assertions") {

                it("asserts the sequence of multiple queries") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "seq-1", value = 1))
                    }
                    tx.readOnly {
                        testEntityRepository.findByName("seq-1")
                    }

                    val queries = hqlRecorder.getRecordedQueries()
                    queries.size shouldBe 1 // Saves are not recorded; only findByName is.
                    hqlRecorder.assertLastQueryContains("WHERE e.name = :p0")
                }
            }

            context("query filtering by type") {

                it("filters queries by type") {
                    tx.transactional {
                        testEntityRepository.save(TestEntity(name = "filter-test", value = 1))
                    }
                    hqlRecorder.clear()

                    tx.readOnly {
                        testEntityRepository.findByName("filter-test")
                        testEntityRepository.count()
                    }

                    hqlRecorder.getQueriesByType(QueryType.SELECT).size shouldBe 1
                    hqlRecorder.getQueriesByType(QueryType.COUNT).size shouldBe 1
                    hqlRecorder.assertQueryCountByType(QueryType.DELETE, 0)
                }
            }
        }
    }
}
