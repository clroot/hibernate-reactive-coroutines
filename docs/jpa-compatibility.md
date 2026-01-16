# JPA 호환성

이 문서는 Hibernate Reactive Coroutines가 JPA 스펙과 어떻게 호환되는지 설명합니다.

## Spring Data JPA 기능 커버리지

### Repository 기본 기능

| 기능                                 | 지원 | 비고                                                  |
| ------------------------------------ | :--: | ----------------------------------------------------- |
| `CrudRepository` 메서드              |  ✅  | save, findById, findAll, delete, count, existsById 등 |
| `findBy*` 메서드 쿼리                |  ✅  | PartTree 기반 자동 생성                               |
| `countBy*`, `existsBy*`, `deleteBy*` |  ✅  |                                                       |
| LIKE 검색                            |  ✅  | Containing, StartingWith, EndingWith                  |
| 비교 연산                            |  ✅  | GreaterThan, LessThan, Between 등                     |
| `@Query` (JPQL)                      |  ✅  | Named/Positional Parameter                            |
| `@Query` (Native)                    |  ✅  | 읽기만, countQuery 명시 필요                          |
| `@Modifying`                         |  ✅  | JPQL UPDATE/DELETE                                    |
| `@Param`                             |  ✅  |                                                       |

### 페이징 및 정렬

| 기능                       | 지원 | 비고                        |
| -------------------------- | :--: | --------------------------- |
| `Page<T>` + `Pageable`     |  ✅  | 스마트 COUNT 스킵 최적화    |
| `Slice<T>`                 |  ✅  | COUNT 없이 다음 페이지 확인 |
| `Sort`                     |  ✅  | 동적 정렬                   |
| 메서드명 정렬 (`OrderBy*`) |  ✅  |                             |

### Auditing

| 기능                | 지원 | 비고                      |
| ------------------- | :--: | ------------------------- |
| `@CreatedDate`      |  ✅  | EntityListener로 처리     |
| `@LastModifiedDate` |  ✅  | EntityListener로 처리     |
| `@CreatedBy`        |  ✅  | ReactiveAuditorAware 필요 |
| `@LastModifiedBy`   |  ✅  | ReactiveAuditorAware 필요 |

### 트랜잭션

| 기능                        | 지원 | 비고                           |
| --------------------------- | :--: | ------------------------------ |
| `@Transactional`            |  ✅  | suspend 함수 지원              |
| readOnly                    |  ✅  |                                |
| timeout                     |  ✅  |                                |
| isolation                   |  ✅  |                                |
| Propagation.REQUIRED        |  ✅  | 기본값                         |
| Propagation.SUPPORTS        |  ✅  |                                |
| Propagation.NOT_SUPPORTED   |  ✅  |                                |
| Propagation.MANDATORY       |  ✅  |                                |
| Propagation.NEVER           |  ✅  |                                |
| Propagation.REQUIRES_NEW    |  ⚠️  | 커넥션 풀 고갈 위험, 중첩 제한 |
| rollbackFor / noRollbackFor |  ✅  |                                |
| Programmatic Transaction    |  ✅  | ReactiveTransactionExecutor    |

### Locking

| 기능                    | 지원 | 비고 |
| ----------------------- | :--: | ---- |
| Optimistic (`@Version`) |  ✅  |      |
| Pessimistic (`@Lock`)   |  ❌  |      |

### JPA 스펙

| 기능                       | 지원 | 비고                       |
| -------------------------- | :--: | -------------------------- |
| Dirty Checking             |  ✅  |                            |
| First-level Cache          |  ✅  |                            |
| Lazy Loading               |  ✅  | fetch() 메서드 사용        |
| Entity Lifecycle Callbacks |  ✅  | @PrePersist, @PreUpdate 등 |
| `Persistable` 인터페이스   |  ✅  |                            |

### 미지원 기능

| 기능                         | 대체 방안                        |
| ---------------------------- | -------------------------------- |
| Specification (동적 쿼리)    | `@Query`로 직접 작성             |
| QueryByExample               | 조건별 메서드 조합               |
| Projection (인터페이스 기반) | `SELECT new DTO(...)` 사용       |
| `@EntityGraph`               | FETCH JOIN 또는 `fetch()` 메서드 |
| Named Query                  | `@Query` 사용                    |
| Stored Procedure             | Native Query (읽기만)            |
| QueryHints                   | -                                |

**전체 커버리지: ~85-90%** - 핵심 기능은 모두 지원되며, 미지원 기능도 대체 방안이 있습니다.

## 지원되는 JPA 동작

### Dirty Checking

트랜잭션 내에서 엔티티를 수정하면 명시적 `save()` 없이도 커밋 시 자동으로 DB에 반영됩니다.

```kotlin
@Transactional
suspend fun updateUser(id: Long, newName: String) {
    val user = userRepository.findById(id)!!
    user.name = newName  // save() 호출 없이 자동 반영
}
```

### First-level Cache

같은 트랜잭션 내에서 동일 ID로 조회하면 같은 인스턴스가 반환됩니다.

```kotlin
@Transactional
suspend fun verify(id: Long) {
    val user1 = userRepository.findById(id)
    val user2 = userRepository.findById(id)
    assert(user1 === user2)  // 동일 인스턴스
}
```

### Optimistic Locking

`@Version` 필드가 자동으로 관리됩니다.

```kotlin
@Entity
class User(
    @Id @GeneratedValue
    val id: Long? = null,
    var name: String,
    @Version
    var version: Long? = null
)
```

동시 수정 시 `OptimisticLockException`이 발생합니다.

### Auto-flush Before Query

쿼리 실행 전에 변경사항이 자동으로 flush됩니다.

```kotlin
@Transactional
suspend fun test() {
    val user = userRepository.save(User(name = "test"))
    val found = userRepository.findByName("test")  // 방금 저장한 user가 조회됨
}
```

## 제약사항

### Lazy Loading

**Hibernate Reactive의 근본적 제약**으로 동기적 Lazy Loading이 지원되지 않습니다.

```kotlin
// 작동하지 않음
val parent = parentRepository.findById(id)!!
parent.children.size  // HR000069 에러 발생
```

**해결 방법:**

1. **FETCH JOIN** (권장)

   ```kotlin
   @Query("SELECT p FROM Parent p LEFT JOIN FETCH p.children WHERE p.id = :id")
   suspend fun findByIdWithChildren(id: Long): Parent?
   ```

2. **fetch() 메서드**

   ```kotlin
   val children = sessionProvider.fetch(parent, Parent::children)
   ```

3. **fetchAll() 메서드** (여러 연관관계)
   ```kotlin
   sessionProvider.fetchAll(order, Order::items, Order::payments)
   ```

### REQUIRES_NEW

새 커넥션이 필요한 `REQUIRES_NEW` 전파는 지원되지 않습니다. 리액티브 환경에서 커넥션 풀 고갈 위험이 있기 때문입니다.

**대안:**

- 이벤트 기반 분리
- 메시지 큐 사용
- 서비스 분리

### Native Query @Modifying

Native Query의 UPDATE/DELETE는 Hibernate Reactive API 제약으로 지원되지 않습니다.

```kotlin
// 지원됨
@Modifying
@Query("UPDATE User u SET u.status = :status WHERE u.id = :id")
suspend fun updateStatus(id: Long, status: Status): Int

// 지원되지 않음
@Modifying
@Query(value = "UPDATE users SET status = ?1 WHERE id = ?2", nativeQuery = true)
suspend fun updateStatusNative(status: String, id: Long): Int
```

## 마이그레이션 체크리스트

Spring Data JPA에서 전환 시 확인할 사항:

- [ ] Lazy Loading 코드를 FETCH JOIN 또는 `fetch()` 메서드로 변경
- [ ] `REQUIRES_NEW` 사용 부분 리팩토링
- [ ] Native @Modifying 쿼리를 JPQL로 변경
- [ ] `@EntityGraph` 사용 부분을 FETCH JOIN으로 변경
- [ ] 모든 Repository 메서드에 `suspend` 키워드 추가
- [ ] Service 메서드에 `suspend` 키워드 및 `@Transactional` 확인
