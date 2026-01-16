package io.clroot.hibernate.reactive.spring.boot.repository.query

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.data.domain.Sort
import org.springframework.data.repository.query.parser.PartTree

/**
 * PartTreeHqlBuilder 유닛 테스트.
 *
 * Spring Data Commons의 PartTree를 HQL로 변환하는 기능을 검증합니다.
 */
class PartTreeHqlBuilderTest : DescribeSpec({

    // 테스트용 엔티티 클래스
    data class User(
        val id: Long,
        val name: String,
        val email: String,
        val age: Int,
        val active: Boolean,
    )

    describe("PartTreeHqlBuilder") {

        context("SELECT 쿼리") {

            it("findByName - 단순 조건") {
                val partTree = PartTree("findByName", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.name = :p0"
                result.parameterBinders shouldHaveSize 1
                result.parameterBinders[0] shouldBe ParameterBinder.Direct
            }

            it("findByNameAndEmail - AND 조건") {
                val partTree = PartTree("findByNameAndEmail", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                // AND 조건이 여러 개일 때 괄호로 감싸짐
                result.hql shouldBe "FROM User e WHERE (e.name = :p0 AND e.email = :p1)"
                result.parameterBinders shouldHaveSize 2
            }

            it("findByNameOrEmail - OR 조건") {
                val partTree = PartTree("findByNameOrEmail", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.name = :p0 OR e.email = :p1"
                result.parameterBinders shouldHaveSize 2
            }

            it("findByNameAndAgeOrEmail - 복합 조건") {
                val partTree = PartTree("findByNameAndAgeOrEmail", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                // (name AND age) OR email
                result.hql shouldContain "e.name = :p0 AND e.age = :p1"
                result.hql shouldContain "OR"
                result.hql shouldContain "e.email = :p2"
                result.parameterBinders shouldHaveSize 3
            }
        }

        context("LIKE 패턴 쿼리") {

            it("findByNameContaining - %value% 패턴") {
                val partTree = PartTree("findByNameContaining", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.name LIKE :p0"
                result.parameterBinders shouldHaveSize 1
                result.parameterBinders[0] shouldBe ParameterBinder.Containing
            }

            it("findByNameStartingWith - value% 패턴") {
                val partTree = PartTree("findByNameStartingWith", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.name LIKE :p0"
                result.parameterBinders shouldHaveSize 1
                result.parameterBinders[0] shouldBe ParameterBinder.StartingWith
            }

            it("findByNameEndingWith - %value 패턴") {
                val partTree = PartTree("findByNameEndingWith", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.name LIKE :p0"
                result.parameterBinders shouldHaveSize 1
                result.parameterBinders[0] shouldBe ParameterBinder.EndingWith
            }

            it("findByNameNotContaining - NOT LIKE 패턴") {
                val partTree = PartTree("findByNameNotContaining", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.name NOT LIKE :p0"
                result.parameterBinders[0] shouldBe ParameterBinder.Containing
            }
        }

        context("비교 연산자 쿼리") {

            it("findByAgeGreaterThan") {
                val partTree = PartTree("findByAgeGreaterThan", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.age > :p0"
                result.parameterBinders shouldHaveSize 1
            }

            it("findByAgeLessThan") {
                val partTree = PartTree("findByAgeLessThan", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.age < :p0"
            }

            it("findByAgeGreaterThanEqual") {
                val partTree = PartTree("findByAgeGreaterThanEqual", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.age >= :p0"
            }

            it("findByAgeLessThanEqual") {
                val partTree = PartTree("findByAgeLessThanEqual", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.age <= :p0"
            }

            it("findByAgeBetween - BETWEEN") {
                val partTree = PartTree("findByAgeBetween", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.age BETWEEN :p0 AND :p1"
                result.parameterBinders shouldHaveSize 2
            }
        }

        context("NULL 체크 쿼리") {

            it("findByEmailIsNull") {
                val partTree = PartTree("findByEmailIsNull", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.email IS NULL"
                result.parameterBinders shouldHaveSize 0
            }

            it("findByEmailIsNotNull") {
                val partTree = PartTree("findByEmailIsNotNull", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.email IS NOT NULL"
                result.parameterBinders shouldHaveSize 0
            }
        }

        context("Boolean 쿼리") {

            it("findByActiveTrue") {
                val partTree = PartTree("findByActiveTrue", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.active = TRUE"
                result.parameterBinders shouldHaveSize 0
            }

            it("findByActiveFalse") {
                val partTree = PartTree("findByActiveFalse", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.active = FALSE"
                result.parameterBinders shouldHaveSize 0
            }
        }

        context("IN 쿼리") {

            it("findByNameIn") {
                val partTree = PartTree("findByNameIn", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.name IN :p0"
                result.parameterBinders shouldHaveSize 1
            }

            it("findByNameNotIn") {
                val partTree = PartTree("findByNameNotIn", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.name NOT IN :p0"
            }
        }

        context("정렬 쿼리") {

            it("findByAgeOrderByNameAsc") {
                val partTree = PartTree("findByAgeOrderByNameAsc", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.age = :p0 ORDER BY e.name ASC"
            }

            it("findByAgeOrderByNameDesc") {
                val partTree = PartTree("findByAgeOrderByNameDesc", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.age = :p0 ORDER BY e.name DESC"
            }
        }

        context("COUNT 쿼리") {

            it("countByName") {
                val partTree = PartTree("countByName", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "SELECT COUNT(e) FROM User e WHERE e.name = :p0"
            }

            it("countByActive") {
                val partTree = PartTree("countByActiveTrue", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "SELECT COUNT(e) FROM User e WHERE e.active = TRUE"
            }
        }

        context("EXISTS 쿼리") {

            it("existsByName") {
                val partTree = PartTree("existsByName", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "SELECT COUNT(e) FROM User e WHERE e.name = :p0"
            }

            it("existsByEmail") {
                val partTree = PartTree("existsByEmail", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "SELECT COUNT(e) FROM User e WHERE e.email = :p0"
            }
        }

        context("DELETE 쿼리") {

            it("deleteByName") {
                val partTree = PartTree("deleteByName", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "DELETE FROM User e WHERE e.name = :p0"
            }

            it("deleteByAge") {
                val partTree = PartTree("deleteByAge", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "DELETE FROM User e WHERE e.age = :p0"
            }
        }

        context("부정 조건 쿼리") {

            it("findByNameNot - 부정 조건") {
                val partTree = PartTree("findByNameNot", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.name <> :p0"
            }
        }

        context("Sort 병합") {

            context("동적 Sort가 주어졌을 때") {
                it("동적 Sort를 우선 적용한다") {
                    val partTree = PartTree("findAllByNameOrderByEmailAsc", User::class.java)
                    val builder = PartTreeHqlBuilder("User", partTree)
                    val dynamicSort = Sort.by(Sort.Direction.DESC, "age")

                    val result = builder.buildWithSort(dynamicSort)

                    result.hql shouldContain "ORDER BY e.age DESC"
                    result.hql shouldNotContain "e.email"
                }
            }

            context("동적 Sort가 없을 때") {
                it("메서드명의 정렬을 적용한다") {
                    val partTree = PartTree("findAllByNameOrderByEmailAsc", User::class.java)
                    val builder = PartTreeHqlBuilder("User", partTree)

                    val result = builder.buildWithSort(null)

                    result.hql shouldContain "ORDER BY e.email ASC"
                }
            }

            context("동적 Sort가 unsorted일 때") {
                it("메서드명의 정렬을 적용한다") {
                    val partTree = PartTree("findAllByNameOrderByEmailDesc", User::class.java)
                    val builder = PartTreeHqlBuilder("User", partTree)

                    val result = builder.buildWithSort(Sort.unsorted())

                    result.hql shouldContain "ORDER BY e.email DESC"
                }
            }
        }

        context("buildCountHql") {

            it("SELECT COUNT HQL을 생성한다") {
                val partTree = PartTree("findAllByName", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val countHql = builder.buildCountHql()

                countHql shouldBe "SELECT COUNT(e) FROM User e WHERE e.name = :p0"
            }

            it("복합 조건에 대해서도 COUNT HQL을 생성한다") {
                val partTree = PartTree("findAllByNameAndAge", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val countHql = builder.buildCountHql()

                countHql shouldBe "SELECT COUNT(e) FROM User e WHERE (e.name = :p0 AND e.age = :p1)"
            }

            // Note: PartTree는 "findAll"처럼 조건이 없는 메서드명을 지원하지 않습니다.
            // 조건 없는 쿼리(findAll, count 등)는 SimpleHibernateReactiveRepository에서
            // 기본 CRUD 메서드로 별도 처리됩니다.
        }
    }
})
