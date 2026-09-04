package io.clroot.hibernate.reactive.spring.boot.repository.query

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.data.domain.Sort
import org.springframework.data.repository.query.parser.PartTree

/** Verifies conversion of Spring Data Commons PartTree instances to HQL. */
class PartTreeHqlBuilderTest : DescribeSpec({

    data class User(
        val id: Long,
        val name: String,
        val email: String,
        val age: Int,
        val active: Boolean,
    )

    describe("PartTreeHqlBuilder") {

        context("SELECT queries") {

            it("findByName - simple predicate") {
                val partTree = PartTree("findByName", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.name = :p0"
                result.parameterBinders shouldHaveSize 1
                result.parameterBinders[0] shouldBe ParameterBinder.Direct
            }

            it("findByNameAndEmail - AND predicate") {
                val partTree = PartTree("findByNameAndEmail", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE (e.name = :p0 AND e.email = :p1)"
                result.parameterBinders shouldHaveSize 2
            }

            it("findByNameOrEmail - OR predicate") {
                val partTree = PartTree("findByNameOrEmail", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.name = :p0 OR e.email = :p1"
                result.parameterBinders shouldHaveSize 2
            }

            it("findByNameAndAgeOrEmail - compound predicate") {
                val partTree = PartTree("findByNameAndAgeOrEmail", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldContain "e.name = :p0 AND e.age = :p1"
                result.hql shouldContain "OR"
                result.hql shouldContain "e.email = :p2"
                result.parameterBinders shouldHaveSize 3
            }
        }

        context("LIKE pattern queries") {

            it("findByNameContaining - %value% pattern") {
                val partTree = PartTree("findByNameContaining", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.name LIKE :p0 ESCAPE '\\'"
                result.parameterBinders shouldHaveSize 1
                result.parameterBinders[0] shouldBe ParameterBinder.Containing
            }

            it("findByNameStartingWith - value% pattern") {
                val partTree = PartTree("findByNameStartingWith", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.name LIKE :p0 ESCAPE '\\'"
                result.parameterBinders shouldHaveSize 1
                result.parameterBinders[0] shouldBe ParameterBinder.StartingWith
            }

            it("findByNameEndingWith - %value pattern") {
                val partTree = PartTree("findByNameEndingWith", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.name LIKE :p0 ESCAPE '\\'"
                result.parameterBinders shouldHaveSize 1
                result.parameterBinders[0] shouldBe ParameterBinder.EndingWith
            }

            it("findByNameNotContaining - NOT LIKE pattern") {
                val partTree = PartTree("findByNameNotContaining", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.name NOT LIKE :p0 ESCAPE '\\'"
                result.parameterBinders[0] shouldBe ParameterBinder.Containing
            }
        }

        context("comparison operator queries") {

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

        context("NULL check queries") {

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

        context("Boolean queries") {

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

        context("IN queries") {

            it("findByNameIn") {
                val partTree = PartTree("findByNameIn", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.name IN :p0"
                result.parameterBinders shouldBe listOf(ParameterBinder.InCollection)
            }

            it("findByNameNotIn") {
                val partTree = PartTree("findByNameNotIn", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.name NOT IN :p0"
                result.parameterBinders shouldBe listOf(ParameterBinder.NotInCollection)
            }
        }

        context("sorting queries") {

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

        context("COUNT queries") {

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

        context("EXISTS queries") {

            it("existsByName") {
                val partTree = PartTree("existsByName", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "SELECT 1 FROM User e WHERE e.name = :p0"
            }

            it("existsByEmail") {
                val partTree = PartTree("existsByEmail", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "SELECT 1 FROM User e WHERE e.email = :p0"
            }
        }

        context("unsupported predicates") {

            it("rejects Regex rather than substituting LIKE") {
                val partTree = PartTree("findByNameRegex", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val error = shouldThrow<UnsupportedOperationException> {
                    builder.build()
                }

                error.message shouldBe "Derived query type is not supported: REGEX"
            }

            it("rejects Exists rather than generating invalid HQL") {
                val partTree = PartTree("findByNameExists", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val error = shouldThrow<UnsupportedOperationException> {
                    builder.build()
                }

                error.message shouldBe "Derived query type is not supported: EXISTS"
            }
        }

        context("DELETE queries") {

            it("deleteByName") {
                val partTree = PartTree("deleteByName", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.name = :p0"
            }

            it("deleteByAge") {
                val partTree = PartTree("deleteByAge", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.age = :p0"
            }
        }

        context("negated predicate queries") {

            it("findByNameNot - negated predicate") {
                val partTree = PartTree("findByNameNot", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val result = builder.build()

                result.hql shouldBe "FROM User e WHERE e.name <> :p0"
            }
        }

        context("Sort merging") {

            context("when a dynamic Sort is supplied") {
                it("prioritizes the dynamic Sort") {
                    val partTree = PartTree("findAllByNameOrderByEmailAsc", User::class.java)
                    val builder = PartTreeHqlBuilder("User", partTree)
                    val dynamicSort = Sort.by(Sort.Direction.DESC, "age")

                    val result = builder.buildWithSort(dynamicSort)

                    result.hql shouldContain "ORDER BY e.age DESC"
                    result.hql shouldNotContain "e.email"
                }

                it("converts ignoreCase to LOWER sorting") {
                    val partTree = PartTree("findAllByName", User::class.java)
                    val builder = PartTreeHqlBuilder("User", partTree)
                    val dynamicSort = Sort.by(Sort.Order.by("name").ignoreCase())

                    builder.buildWithSort(dynamicSort).hql shouldContain "ORDER BY LOWER(e.name) ASC"
                }
            }

            context("when no dynamic Sort is supplied") {
                it("applies method-name sorting") {
                    val partTree = PartTree("findAllByNameOrderByEmailAsc", User::class.java)
                    val builder = PartTreeHqlBuilder("User", partTree)

                    val result = builder.buildWithSort(null)

                    result.hql shouldContain "ORDER BY e.email ASC"
                }
            }

            context("when the dynamic Sort is unsorted") {
                it("applies method-name sorting") {
                    val partTree = PartTree("findAllByNameOrderByEmailDesc", User::class.java)
                    val builder = PartTreeHqlBuilder("User", partTree)

                    val result = builder.buildWithSort(Sort.unsorted())

                    result.hql shouldContain "ORDER BY e.email DESC"
                }
            }
        }

        context("buildCountHql") {

            it("builds SELECT COUNT HQL") {
                val partTree = PartTree("findAllByName", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val countHql = builder.buildCountHql()

                countHql shouldBe "SELECT COUNT(e) FROM User e WHERE e.name = :p0"
            }

            it("builds COUNT HQL for compound predicates") {
                val partTree = PartTree("findAllByNameAndAge", User::class.java)
                val builder = PartTreeHqlBuilder("User", partTree)

                val countHql = builder.buildCountHql()

                countHql shouldBe "SELECT COUNT(e) FROM User e WHERE (e.name = :p0 AND e.age = :p1)"
            }

            // PartTree does not support conditionless method names such as "findAll".
            // Conditionless queries are handled as base CRUD methods by SimpleHibernateReactiveRepository.
        }
    }
})
