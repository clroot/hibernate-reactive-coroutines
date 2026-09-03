package io.clroot.hibernate.reactive.spring.boot.repository

import io.clroot.hibernate.reactive.spring.boot.repository.query.PartTreeHqlBuilder
import io.clroot.hibernate.reactive.repository.query.Query
import io.clroot.hibernate.reactive.test.entity.RenamedEntity
import io.clroot.hibernate.reactive.test.entity.TestEntity
import io.clroot.hibernate.reactive.test.repository.RenamedEntityRepository
import io.clroot.hibernate.reactive.test.repository.TestEntityRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.data.repository.query.parser.PartTree

class DerivedQueryParityTest : DescribeSpec({
    describe("framework-neutral compiler parity") {
        it("prepares every current Spring derived-query method exactly as the PartTree compiler did") {
            verifyParity(TestEntityRepository::class.java, TestEntity::class.java, "TestEntity")
            verifyParity(RenamedEntityRepository::class.java, RenamedEntity::class.java, "RenamedAlias")
        }
    }
})

private fun verifyParity(repositoryType: Class<*>, entityType: Class<*>, entityName: String) {
    val parser = QueryMethodParser(entityType, entityName)
    val methods = repositoryType.declaredMethods
        .filter(parser::isCustomQueryMethod)
        .filterNot { it.isAnnotationPresent(Query::class.java) }

    methods.forEach { method ->
        val legacyTree = PartTree(method.name, entityType)
        val legacy = PartTreeHqlBuilder(entityName, legacyTree).build()
        val prepared = parser.parse(method)

        prepared.hql shouldBe legacy.hql
        prepared.parameterBinders shouldBe legacy.parameterBinders
        prepared.maxResults shouldBe legacyTree.maxResults
        if (prepared.countHql != null) {
            prepared.countHql shouldBe PartTreeHqlBuilder(entityName, legacyTree).buildCountHql()
        }
    }
}
