package io.clroot.hibernate.reactive.repository.runtime

import io.clroot.hibernate.reactive.repository.CoroutineCrudRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class RepositoryMethodScannerTest : DescribeSpec({
    describe("query method selection") {
        it("returns only user-declared suspend methods") {
            RepositoryMethodScanner.queryMethods(ValidRepository::class.java)
                .map(RepositoryMethodScanner::methodKey)
                .shouldContainExactlyInAnyOrder("findByName#1", "findByNameAndActive#2", "countByActive#1")
        }

        it("accepts a method redeclared by a sub-interface with the same signature") {
            RepositoryMethodScanner.queryMethods(RedeclaringRepository::class.java)
                .map(RepositoryMethodScanner::methodKey)
                .distinct()
                .shouldContainExactlyInAnyOrder("findByName#1", "findByNameAndActive#2", "countByActive#1")
        }
    }

    describe("startup validation") {
        it("rejects a non-suspend query method") {
            val error = shouldThrow<IllegalStateException> {
                RepositoryMethodScanner.queryMethods(NonSuspendRepository::class.java)
            }

            error.message shouldContain "NonSuspendRepository.findByName' must be a suspend function"
        }

        it("rejects a Flow-returning non-suspend query method") {
            val error = shouldThrow<IllegalStateException> {
                RepositoryMethodScanner.queryMethods(FlowRepository::class.java)
            }

            error.message shouldContain "FlowRepository.streamByActive' must be a suspend function"
        }

        it("rejects overloads that share a name and argument count") {
            val error = shouldThrow<IllegalStateException> {
                RepositoryMethodScanner.queryMethods(AmbiguousRepository::class.java)
            }

            error.message shouldContain "ambiguous overloads for 'findByName#1'"
        }

        it("accepts overloads with different argument counts") {
            RepositoryMethodScanner.queryMethods(ArityOverloadRepository::class.java)
                .map(RepositoryMethodScanner::methodKey)
                .shouldContainExactlyInAnyOrder("findByName#1", "findByName#2")
        }

        it("ignores default methods") {
            RepositoryMethodScanner.queryMethods(DefaultMethodRepository::class.java)
                .map(RepositoryMethodScanner::methodKey)
                .shouldContainExactlyInAnyOrder("findByName#1")
        }
    }
})

private class ScannedEntity(val id: Long, val name: String, val active: Boolean)

private interface ValidRepository : CoroutineCrudRepository<ScannedEntity, Long> {
    suspend fun findByName(name: String): List<ScannedEntity>

    suspend fun findByNameAndActive(name: String, active: Boolean): List<ScannedEntity>

    suspend fun countByActive(active: Boolean): Long
}

private interface RedeclaringRepository : ValidRepository {
    override suspend fun findByName(name: String): List<ScannedEntity>
}

private interface NonSuspendRepository : CoroutineCrudRepository<ScannedEntity, Long> {
    fun findByName(name: String): List<ScannedEntity>
}

private interface FlowRepository : CoroutineCrudRepository<ScannedEntity, Long> {
    fun streamByActive(active: Boolean): Flow<ScannedEntity>
}

private interface AmbiguousRepository : CoroutineCrudRepository<ScannedEntity, Long> {
    suspend fun findByName(name: String): List<ScannedEntity>

    suspend fun findByName(name: Int): List<ScannedEntity>
}

private interface ArityOverloadRepository : CoroutineCrudRepository<ScannedEntity, Long> {
    suspend fun findByName(name: String): List<ScannedEntity>

    suspend fun findByName(name: String, active: Boolean): List<ScannedEntity>
}

private interface DefaultMethodRepository : CoroutineCrudRepository<ScannedEntity, Long> {
    suspend fun findByName(name: String): List<ScannedEntity>

    fun streamAll(): Flow<ScannedEntity> = emptyFlow()
}
