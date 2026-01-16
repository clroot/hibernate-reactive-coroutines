package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.spring.boot.repository.EnableHibernateReactiveRepositories
import io.clroot.hibernate.reactive.test.service.JpaSpecTestService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.ComponentScan

/**
 * JPA 스펙 호환성 검증 테스트.
 *
 * 이 테스트는 Hibernate Reactive Coroutines가 JPA 스펙과 동일하게 동작하는지 검증합니다.
 * Spring Data JPA 사용자가 기대하는 동작과 일치하는지 확인하는 것이 목적입니다.
 */
@SpringBootTest
@EnableHibernateReactiveRepositories(basePackages = ["io.clroot.hibernate.reactive.test.repository"])
@ComponentScan(basePackages = ["io.clroot.hibernate.reactive.test.service"])
class JpaSpecCompatibilityTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jpaSpecTestService: JpaSpecTestService

    init {
        describe("Dirty Checking") {
            context("트랜잭션 내에서 엔티티를 수정하면") {
                it("명시적 save 없이도 트랜잭션 커밋 시 자동으로 DB에 반영된다") {
                    // Given: 엔티티 저장
                    val saved = jpaSpecTestService.saveEntity("original", 100)
                    val id = saved.id!!

                    // When: 트랜잭션 내에서 엔티티 수정 (save 호출 없음)
                    jpaSpecTestService.modifyEntityWithoutSave(id, "modified", 200)

                    // Then: 변경사항이 DB에 반영되어야 함
                    val reloaded = jpaSpecTestService.findById(id)
                    reloaded shouldNotBe null
                    reloaded!!.name shouldBe "modified"
                    reloaded.value shouldBe 200
                }

                it("여러 필드를 수정해도 모두 반영된다") {
                    // Given
                    val saved = jpaSpecTestService.saveEntity("test", 50)
                    val id = saved.id!!

                    // When: 여러 필드 수정
                    jpaSpecTestService.modifyMultipleFields(id, "newName", 999)

                    // Then
                    val reloaded = jpaSpecTestService.findById(id)
                    reloaded!!.name shouldBe "newName"
                    reloaded.value shouldBe 999
                }
            }

            context("readOnly 트랜잭션에서 엔티티를 수정하면") {
                it("변경사항이 DB에 반영되지 않는다") {
                    // Given
                    val saved = jpaSpecTestService.saveEntity("readonly-test", 100)
                    val id = saved.id!!

                    // When: readOnly 트랜잭션에서 수정 시도
                    jpaSpecTestService.modifyInReadOnlyTransaction(id, "should-not-persist", 999)

                    // Then: 원본 값 유지
                    val reloaded = jpaSpecTestService.findById(id)
                    reloaded!!.name shouldBe "readonly-test"
                    reloaded.value shouldBe 100
                }
            }

            context("트랜잭션이 롤백되면") {
                it("Dirty Checking으로 감지된 변경사항도 롤백된다") {
                    // Given
                    val saved = jpaSpecTestService.saveEntity("rollback-test", 100)
                    val id = saved.id!!

                    // When: 수정 후 예외 발생으로 롤백
                    runCatching {
                        jpaSpecTestService.modifyAndThrowException(id, "should-rollback", 999)
                    }

                    // Then: 원본 값 유지
                    val reloaded = jpaSpecTestService.findById(id)
                    reloaded!!.name shouldBe "rollback-test"
                    reloaded.value shouldBe 100
                }
            }
        }

        describe("First-level Cache (영속성 컨텍스트)") {
            context("같은 트랜잭션 내에서 같은 ID로 조회하면") {
                it("동일한 객체 인스턴스를 반환한다") {
                    // Given
                    val saved = jpaSpecTestService.saveEntity("cache-test", 100)
                    val id = saved.id!!

                    // When & Then: 같은 트랜잭션에서 두 번 조회
                    jpaSpecTestService.verifyFirstLevelCache(id)
                }
            }

            context("다른 트랜잭션에서 조회하면") {
                it("다른 객체 인스턴스를 반환한다") {
                    // Given
                    val saved = jpaSpecTestService.saveEntity("cache-test-2", 100)
                    val id = saved.id!!

                    // When: 서로 다른 트랜잭션에서 조회
                    val entity1 = jpaSpecTestService.findById(id)
                    val entity2 = jpaSpecTestService.findById(id)

                    // Then: 다른 인스턴스 (서로 다른 트랜잭션이므로)
                    (entity1 !== entity2) shouldBe true
                    entity1!!.name shouldBe entity2!!.name
                }
            }
        }

        describe("Optimistic Locking (@Version)") {
            context("@Version 필드가 있는 엔티티를 저장하면") {
                it("버전이 자동으로 증가한다") {
                    // Given & When
                    val saved = jpaSpecTestService.saveVersionedEntity("version-test", 100)

                    // Then: 초기 버전
                    saved.version shouldBe 0L

                    // When: 수정
                    val updated = jpaSpecTestService.updateVersionedEntity(saved.id!!, "updated", 200)

                    // Then: 버전 증가
                    updated.version shouldBe 1L
                }
            }

            context("동시에 같은 엔티티를 수정하면") {
                it("OptimisticLockException이 발생한다") {
                    // Given: 엔티티 저장
                    val saved = jpaSpecTestService.saveVersionedEntity("concurrent-test", 100)
                    val id = saved.id!!

                    // When: 동시 수정 시도
                    shouldThrow<Exception> {
                        jpaSpecTestService.concurrentUpdate(id)
                    }
                }
            }
        }

        describe("Lazy Loading") {
            context("Hibernate Reactive 환경에서") {
                it("같은 트랜잭션 내에서 Lazy 컬렉션에 접근할 수 있다") {
                    // Given: 부모-자식 관계 저장
                    val parentId = jpaSpecTestService.saveParentWithChildren("parent", listOf("child1", "child2"))

                    // When & Then: 트랜잭션 내에서 Lazy 컬렉션 접근
                    val childCount = jpaSpecTestService.getChildCountInTransaction(parentId)
                    childCount shouldBe 2
                }

                it("JOIN FETCH로 Eager loading하면 트랜잭션 외부에서도 접근 가능하다") {
                    // Given
                    val parentId =
                        jpaSpecTestService.saveParentWithChildren("parent", listOf("child1", "child2", "child3"))

                    // When: JOIN FETCH로 조회
                    val parent = jpaSpecTestService.findParentWithChildrenEager(parentId)

                    // Then: 트랜잭션 외부에서도 자식 접근 가능
                    parent shouldNotBe null
                    parent!!.children.size shouldBe 3
                }
            }

            context("sessionProvider.fetch() 편의 메서드를 사용하면") {
                it("Lazy 연관관계를 간단히 로딩할 수 있다") {
                    // Given
                    val parentId = jpaSpecTestService.saveParentWithChildren("fetch-test", listOf("a", "b", "c"))

                    // When: fetch() 메서드로 Lazy 컬렉션 로딩
                    val children = jpaSpecTestService.getChildrenUsingFetch(parentId)

                    // Then
                    children.size shouldBe 3
                    children.map { it.name } shouldBe listOf("a", "b", "c")
                }
            }

            context("sessionProvider.fetchAll() 편의 메서드를 사용하면") {
                it("여러 Lazy 연관관계를 한 번에 로딩할 수 있다") {
                    // Given
                    val parentId = jpaSpecTestService.saveParentWithChildren("fetchAll-test", listOf("x", "y"))

                    // When: fetchAll()로 여러 연관관계 로딩
                    val parent = jpaSpecTestService.getParentWithAllAssociations(parentId)

                    // Then: 연관관계가 초기화되어 있음
                    parent.children.size shouldBe 2
                }
            }

            context("sessionProvider.fetchFromDetached() 편의 메서드를 사용하면") {
                it("detached 엔티티의 Lazy 연관관계를 로딩할 수 있다") {
                    // Given: 부모 저장 후 detached 상태로 만듦
                    val parentId = jpaSpecTestService.saveParentWithChildren("detached-test", listOf("d1", "d2"))
                    val detachedParent = jpaSpecTestService.findParentWithChildrenEager(parentId)!!

                    // When: detached 엔티티에서 연관관계 로딩
                    val children = jpaSpecTestService.getChildrenFromDetachedParent(detachedParent)

                    // Then
                    children.size shouldBe 2
                }
            }
        }

        describe("Flush 동작") {
            context("트랜잭션 커밋 전에") {
                it("변경사항이 flush되어 쿼리 결과에 반영된다") {
                    // Given & When & Then
                    jpaSpecTestService.verifyFlushBeforeQuery()
                }
            }
        }
    }
}
