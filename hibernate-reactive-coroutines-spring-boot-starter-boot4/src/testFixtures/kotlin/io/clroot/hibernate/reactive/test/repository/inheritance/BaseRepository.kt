package io.clroot.hibernate.reactive.test.repository.inheritance

import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.NoRepositoryBean

/**
 * 상속 테스트를 위한 베이스 Repository 인터페이스.
 *
 * @NoRepositoryBean으로 마킹하여 직접 Bean으로 등록되지 않도록 합니다.
 */
@NoRepositoryBean
interface BaseRepository<T : Any, ID : Any> : CoroutineCrudRepository<T, ID> {

    /**
     * 이름으로 엔티티 조회 - 하위 Repository에서 상속됨
     */
    suspend fun findByName(name: String): T?

    /**
     * 이름으로 존재 여부 확인 - 하위 Repository에서 상속됨
     */
    suspend fun existsByName(name: String): Boolean
}
