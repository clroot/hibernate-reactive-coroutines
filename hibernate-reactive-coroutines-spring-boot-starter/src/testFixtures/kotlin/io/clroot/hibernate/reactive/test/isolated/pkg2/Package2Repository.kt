package io.clroot.hibernate.reactive.test.isolated.pkg2

import io.clroot.hibernate.reactive.test.entity.TestEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * pkg2 패키지의 테스트용 Repository
 * basePackages 스캔 테스트에 사용
 */
interface Package2Repository : CoroutineCrudRepository<TestEntity, Long> {
    suspend fun findByValue(value: Int): List<TestEntity>
}
