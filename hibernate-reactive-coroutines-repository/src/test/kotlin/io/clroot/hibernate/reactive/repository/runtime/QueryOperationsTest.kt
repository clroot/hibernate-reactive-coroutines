package io.clroot.hibernate.reactive.repository.runtime

import io.clroot.hibernate.reactive.ReactiveSessionOperations
import io.clroot.hibernate.reactive.repository.query.QueryParameterStyle
import io.clroot.hibernate.reactive.repository.query.derived.QueryOrder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.hibernate.reactive.mutiny.Mutiny
import jakarta.persistence.metamodel.Attribute
import jakarta.persistence.metamodel.ManagedType
import jakarta.persistence.metamodel.Metamodel
import jakarta.persistence.metamodel.PluralAttribute
import jakarta.persistence.metamodel.SingularAttribute
import jakarta.persistence.metamodel.Type

class QueryOperationsTest : DescribeSpec({

    val metamodel = mockk<Metamodel>()
    val userType = mockk<ManagedType<User>>()
    val addressType = mockk<ManagedType<Address>>()
    val nameAttribute = mockk<SingularAttribute<User, String>>()
    val ageAttribute = mockk<SingularAttribute<User, Int>>()
    val addressAttribute = mockk<SingularAttribute<User, Address>>()
    val addressesAttribute = mockk<PluralAttribute<User, List<Address>, Address>>()
    val addressAttributeType = mockk<Type<Address>>()
    val cityAttribute = mockk<SingularAttribute<Address, String>>()

    every { metamodel.managedType(User::class.java) } returns userType
    every { metamodel.managedType(Address::class.java) } returns addressType
    every { userType.getAttribute("name") } returns nameAttribute
    every { userType.getAttribute("age") } returns ageAttribute
    every { userType.getAttribute("address") } returns addressAttribute
    every { userType.getAttribute("addresses") } returns addressesAttribute
    every { addressAttribute.type } returns addressAttributeType
    every { addressAttributeType.javaType } returns Address::class.java
    every { addressType.getAttribute("city") } returns cityAttribute
    every { cityAttribute.persistentAttributeType } returns Attribute.PersistentAttributeType.BASIC
    every { cityAttribute.javaType } returns String::class.java
    every { nameAttribute.persistentAttributeType } returns Attribute.PersistentAttributeType.BASIC
    every { nameAttribute.javaType } returns String::class.java
    every { ageAttribute.persistentAttributeType } returns Attribute.PersistentAttributeType.BASIC
    every { ageAttribute.javaType } returns Int::class.java
    every { userType.getAttribute("computed") } throws IllegalArgumentException()
    every { userType.getAttribute("doesNotExist") } throws IllegalArgumentException()

    val operations = QueryOperations(
        User::class.java,
        mockk<ReactiveSessionOperations>(),
        metamodel,
    )

    describe("dynamic sort") {
        it("resolves a known nested property") {
            operations.buildSortClause(listOf(QueryOrder("address.city"))) shouldBe "e.address.city ASC"
        }

        it("applies ignoreCase with LOWER to a String property") {
            operations.buildSortClause(listOf(QueryOrder("name", ignoreCase = true))) shouldBe
                    "LOWER(e.name) ASC"
        }

        it("rejects ignoreCase on a non-String property") {
            shouldThrow<IllegalArgumentException> {
                operations.buildSortClause(listOf(QueryOrder("age", ignoreCase = true)))
            }
        }

        it("resolves a JPA field-access property without a JavaBean getter") {
            val fieldMetamodel = mockk<Metamodel>()
            val fieldType = mockk<ManagedType<FieldAccessUser>>()
            val fieldAttribute = mockk<SingularAttribute<FieldAccessUser, String>>()
            every { fieldMetamodel.managedType(FieldAccessUser::class.java) } returns fieldType
            every { fieldType.getAttribute("name") } returns fieldAttribute
            every { fieldAttribute.persistentAttributeType } returns Attribute.PersistentAttributeType.BASIC
            every { fieldAttribute.javaType } returns String::class.java
            val fieldOperations = QueryOperations(
                FieldAccessUser::class.java,
                mockk<ReactiveSessionOperations>(),
                fieldMetamodel,
            )

            fieldOperations.buildSortClause(listOf(QueryOrder("name"))) shouldBe "e.name ASC"
        }

        it("rejects a syntactically valid property that is not part of the persistence model") {
            shouldThrow<IllegalArgumentException> {
                operations.buildSortClause(listOf(QueryOrder("doesNotExist")))
            }
        }

        it("rejects a computed JavaBean property that is not persistent") {
            shouldThrow<IllegalArgumentException> {
                operations.buildSortClause(listOf(QueryOrder("computed")))
            }
        }

        it("rejects a collection-valued sort property") {
            shouldThrow<IllegalArgumentException> {
                operations.buildSortClause(listOf(QueryOrder("addresses")))
            }
        }

        it("rejects traversal through a collection without an explicit join") {
            shouldThrow<IllegalArgumentException> {
                operations.buildSortClause(listOf(QueryOrder("addresses.city")))
            }
        }

        it("rejects a managed association as the terminal sort property") {
            every { addressAttribute.persistentAttributeType } returns Attribute.PersistentAttributeType.MANY_TO_ONE

            shouldThrow<IllegalArgumentException> {
                operations.buildSortClause(listOf(QueryOrder("address")))
            }
        }

        it("rejects a property that can be interpreted as HQL") {
            val sort = listOf(QueryOrder("name) desc, (select count(e2) from User e2"))

            shouldThrow<IllegalArgumentException> {
                operations.buildSortClause(sort)
            }
        }
    }

    describe("annotated count parameters") {
        it("binds only parameters referenced by the count query") {
            val query = mockk<Mutiny.SelectionQuery<Long>>(relaxed = true)
            val prepared = PreparedRepositoryQuery(
                methodName = "findPage",
                hql = "FROM User e ORDER BY CASE WHEN :priority = true THEN 0 ELSE 1 END",
                countHql = "SELECT COUNT(e) FROM User e WHERE e.active = :active",
                parameterBindings = emptyList(),
                returnType = RepositoryQueryReturnType.PAGE,
                parameterStyle = QueryParameterStyle.NAMED,
                parameterNames = listOf("active", "priority"),
            )

            operations.bindAnnotatedCountParameters(query, prepared, listOf(true, false))

            verify(exactly = 1) { query.setParameter("active", true) }
            verify(exactly = 0) { query.setParameter("priority", any<Boolean>()) }
        }

        it("binds only positional parameters referenced by the count query") {
            val query = mockk<Mutiny.SelectionQuery<Long>>(relaxed = true)
            val prepared = PreparedRepositoryQuery(
                methodName = "findPage",
                hql = "FROM User e WHERE e.name = ?1 ORDER BY CASE WHEN ?2 = true THEN 0 ELSE 1 END",
                countHql = "SELECT COUNT(e) FROM User e WHERE e.name = ?1",
                parameterBindings = emptyList(),
                returnType = RepositoryQueryReturnType.PAGE,
                parameterStyle = QueryParameterStyle.POSITIONAL,
            )

            operations.bindAnnotatedCountParameters(query, prepared, listOf("alice", true))

            verify(exactly = 1) { query.setParameter(1, "alice") }
            verify(exactly = 0) { query.setParameter(2, any<Boolean>()) }
        }
    }
}) {
    data class User(val name: String, val address: Address, val addresses: List<Address> = emptyList()) {
        val computed: String
            get() = name.uppercase()
    }

    data class Address(val city: String)
    class FieldAccessUser(@JvmField val name: String)
}
