package io.clroot.hibernate.reactive.test.repository.inheritance

import io.clroot.hibernate.reactive.test.entity.TestEntity

/**
 * BaseRepository를 상속받는 테스트용 Repository.
 *
 * 부모 인터페이스의 메서드(findByName, existsByName)와
 * 자체 정의한 메서드(findAllByValue)를 모두 사용할 수 있습니다.
 */
interface InheritedTestEntityRepository : BaseRepository<TestEntity, Long> {

    /**
     * value로 엔티티 목록 조회 - 이 Repository에서 정의한 메서드
     */
    suspend fun findAllByValue(value: Int): List<TestEntity>
}
