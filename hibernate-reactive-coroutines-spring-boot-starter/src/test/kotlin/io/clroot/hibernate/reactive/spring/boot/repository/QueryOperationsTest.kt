package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.spring.boot.transaction.TransactionalAwareSessionProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import jakarta.persistence.metamodel.Attribute
import jakarta.persistence.metamodel.ManagedType
import jakarta.persistence.metamodel.Metamodel
import jakarta.persistence.metamodel.PluralAttribute
import jakarta.persistence.metamodel.SingularAttribute
import jakarta.persistence.metamodel.Type
import org.springframework.data.domain.Sort

class QueryOperationsTest : DescribeSpec({

    val metamodel = mockk<Metamodel>()
    val userType = mockk<ManagedType<User>>()
    val addressType = mockk<ManagedType<Address>>()
    val nameAttribute = mockk<SingularAttribute<User, String>>()
    val addressAttribute = mockk<SingularAttribute<User, Address>>()
    val addressesAttribute = mockk<PluralAttribute<User, List<Address>, Address>>()
    val addressAttributeType = mockk<Type<Address>>()
    val cityAttribute = mockk<SingularAttribute<Address, String>>()

    every { metamodel.managedType(User::class.java) } returns userType
    every { metamodel.managedType(Address::class.java) } returns addressType
    every { userType.getAttribute("name") } returns nameAttribute
    every { userType.getAttribute("address") } returns addressAttribute
    every { userType.getAttribute("addresses") } returns addressesAttribute
    every { addressAttribute.type } returns addressAttributeType
    every { addressAttributeType.javaType } returns Address::class.java
    every { addressType.getAttribute("city") } returns cityAttribute
    every { cityAttribute.persistentAttributeType } returns Attribute.PersistentAttributeType.BASIC
    every { nameAttribute.persistentAttributeType } returns Attribute.PersistentAttributeType.BASIC
    every { userType.getAttribute("computed") } throws IllegalArgumentException()
    every { userType.getAttribute("doesNotExist") } throws IllegalArgumentException()

    val operations = QueryOperations(
        User::class.java,
        mockk<TransactionalAwareSessionProvider>(),
        metamodel,
    )

    describe("dynamic sort") {
        it("resolves a known nested property") {
            val sort = Sort.by(Sort.Direction.ASC, "address.city")

            operations.buildSortClause(sort) shouldBe "e.address.city ASC"
        }

        it("resolves a JPA field-access property without a JavaBean getter") {
            val fieldMetamodel = mockk<Metamodel>()
            val fieldType = mockk<ManagedType<FieldAccessUser>>()
            val fieldAttribute = mockk<SingularAttribute<FieldAccessUser, String>>()
            every { fieldMetamodel.managedType(FieldAccessUser::class.java) } returns fieldType
            every { fieldType.getAttribute("name") } returns fieldAttribute
            every { fieldAttribute.persistentAttributeType } returns Attribute.PersistentAttributeType.BASIC
            val fieldOperations = QueryOperations(
                FieldAccessUser::class.java,
                mockk<TransactionalAwareSessionProvider>(),
                fieldMetamodel,
            )

            fieldOperations.buildSortClause(Sort.by("name")) shouldBe "e.name ASC"
        }

        it("rejects a syntactically valid property that is not part of the persistence model") {
            shouldThrow<IllegalArgumentException> {
                operations.buildSortClause(Sort.by("doesNotExist"))
            }
        }

        it("rejects a computed JavaBean property that is not persistent") {
            shouldThrow<IllegalArgumentException> {
                operations.buildSortClause(Sort.by("computed"))
            }
        }

        it("rejects a collection-valued sort property") {
            shouldThrow<IllegalArgumentException> {
                operations.buildSortClause(Sort.by("addresses"))
            }
        }

        it("rejects traversal through a collection without an explicit join") {
            shouldThrow<IllegalArgumentException> {
                operations.buildSortClause(Sort.by("addresses.city"))
            }
        }

        it("rejects a managed association as the terminal sort property") {
            every { addressAttribute.persistentAttributeType } returns Attribute.PersistentAttributeType.MANY_TO_ONE

            shouldThrow<IllegalArgumentException> {
                operations.buildSortClause(Sort.by("address"))
            }
        }

        it("rejects a property that can be interpreted as HQL") {
            val sort = Sort.by("name) desc, (select count(e2) from User e2")

            shouldThrow<IllegalArgumentException> {
                operations.buildSortClause(sort)
            }
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
