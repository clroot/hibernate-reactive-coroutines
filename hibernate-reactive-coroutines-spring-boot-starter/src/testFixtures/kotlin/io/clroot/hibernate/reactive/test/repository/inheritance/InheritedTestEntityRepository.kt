package io.clroot.hibernate.reactive.test.repository.inheritance

import io.clroot.hibernate.reactive.test.entity.TestEntity

/**
 * BaseRepository를 상속한 테스트용 Repository.
 *
 * 제네릭 타입 정보 보존과 상속된 메서드 동작을 검증합니다.
 */
interface InheritedTestEntityRepository : BaseRepository<TestEntity, Long> {

    /**
     * 이 Repository에서 직접 정의한 메서드
     */
    suspend fun findAllByValue(value: Int): List<TestEntity>
}
