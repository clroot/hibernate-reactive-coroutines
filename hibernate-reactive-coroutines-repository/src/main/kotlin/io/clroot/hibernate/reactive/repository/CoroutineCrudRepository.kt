package io.clroot.hibernate.reactive.repository

import jakarta.data.Order
import jakarta.data.Sort
import jakarta.data.page.Page
import jakarta.data.page.PageRequest
import jakarta.data.repository.DataRepository
import kotlinx.coroutines.flow.Flow

/**
 * Coroutine-oriented repository contract for integrations that do not use Spring Data.
 *
 * Jakarta Data's marker and paging/sorting types are reused where they fit the coroutine model.
 * CRUD methods use `suspend` and [Flow] because Jakarta Data's synchronous repository contracts
 * cannot represent non-blocking Hibernate Reactive operations.
 */
public interface CoroutineCrudRepository<T : Any, ID : Any> : DataRepository<T, ID> {
    public suspend fun <S : T> save(entity: S): S

    public fun <S : T> saveAll(entities: Iterable<S>): Flow<S>

    public fun <S : T> saveAll(entityStream: Flow<S>): Flow<S>

    public suspend fun findById(id: ID): T?

    public suspend fun existsById(id: ID): Boolean

    public fun findAll(): Flow<T>

    public suspend fun findAll(sort: Sort<T>): List<T>

    public suspend fun findAll(order: Order<T>): List<T>

    public suspend fun findAll(pageRequest: PageRequest): Page<T>

    public suspend fun findAll(pageRequest: PageRequest, sort: Sort<T>): Page<T>

    public suspend fun findAll(pageRequest: PageRequest, order: Order<T>): Page<T>

    public fun findAllById(ids: Iterable<ID>): Flow<T>

    public fun findAllById(idStream: Flow<ID>): Flow<T>

    public suspend fun count(): Long

    public suspend fun deleteById(id: ID)

    public suspend fun delete(entity: T)

    public suspend fun deleteAllById(ids: Iterable<ID>)

    public suspend fun deleteAll(entities: Iterable<T>)

    public suspend fun <S : T> deleteAll(entityStream: Flow<S>)

    public suspend fun deleteAll()
}
