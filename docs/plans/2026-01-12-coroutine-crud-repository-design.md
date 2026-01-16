# CoroutineCrudRepository 호환 설계

## 개요

Spring Data Commons의 `CoroutineCrudRepository`를 직접 사용하도록 변경하여 Spring Data 생태계와 완전 호환되게 한다.

## 주요 결정사항

1. **ReactiveRepository 삭제** - 사용자가 `CoroutineCrudRepository<T, ID>` 직접 상속
2. **모든 메서드 구현** - CoroutineCrudRepository의 모든 메서드 지원
3. **Flow 반환** - `findAll()` 등은 `Flow<T>` 반환 (한 번에 가져와서 변환)

## 사용자 코드 변경

```kotlin
// Before
interface UserRepository : ReactiveRepository<User, Long>

// After
interface UserRepository : CoroutineCrudRepository<User, Long>

// findAll 사용
val users: List<User> = userRepository.findAll().toList()
```

## 구현할 메서드

| 메서드 | 반환 타입 | 구현 방식 |
|--------|----------|----------|
| `save(entity)` | `T` | 기존 유지 (merge) |
| `saveAll(entities: Iterable)` | `Flow<S>` | 루프 돌며 save → Flow 변환 |
| `saveAll(entityStream: Flow)` | `Flow<S>` | collect 후 saveAll 호출 |
| `findById(id)` | `T?` | 기존 유지 |
| `findAll()` | `Flow<T>` | resultList → asFlow() |
| `findAllById(ids: Iterable)` | `Flow<T>` | IN 쿼리 → asFlow() |
| `findAllById(ids: Flow)` | `Flow<T>` | collect 후 findAllById 호출 |
| `existsById(id)` | `Boolean` | 기존 유지 |
| `count()` | `Long` | 기존 유지 |
| `deleteById(id)` | `Unit` | 기존 유지 |
| `delete(entity)` | `Unit` | 기존 유지 |
| `deleteAllById(ids)` | `Unit` | 루프 돌며 deleteById |
| `deleteAll(entities: Iterable)` | `Unit` | 루프 돌며 delete |
| `deleteAll(entityStream: Flow)` | `Unit` | collect 후 deleteAll 호출 |
| `deleteAll()` | `Unit` | DELETE FROM 쿼리 |

## 파일 변경

| 파일 | 변경 내용 |
|------|----------|
| `ReactiveRepository.kt` | 삭제 |
| `SimpleHibernateReactiveRepository.kt` | 새 메서드 구현, findAll → Flow 변경 |
| `HibernateReactiveRepositoryFactoryBean.kt` | CoroutineCrudRepository 스캔 |
| `HibernateReactiveRepositoryTypeFilter.kt` | 필터 대상 변경 |
| `build.gradle.kts` | kotlinx-coroutines-reactive 의존성 추가 |

## 의존성 추가

```kotlin
// kotlinx-coroutines-reactive (Flow 변환용)
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive")
```

## Breaking Changes

- 버전: 0.3.0
- `ReactiveRepository` → `CoroutineCrudRepository` 변경
- `findAll(): List<T>` → `findAll(): Flow<T>` 변경
