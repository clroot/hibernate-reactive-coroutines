# 테스트 보강 계획

> 프로덕션 준비 상태 증명을 위한 테스트 보강 계획

## 배경

현재 테스트 커버리지 분석 결과:

| 영역 | 현재 상태 | 목표 |
|-----|---------|-----|
| Core 모듈 | 0% | 80%+ |
| Spring Boot Starter | ~75% | 90%+ |
| Edge Cases | 부분적 | 포괄적 |
| 성능 테스트 | 없음 | 기본 벤치마크 |

Spring Data JPA/R2DBC의 테스트 구조를 참고하여 보강 계획을 수립합니다.

---

## Phase 1: Core 모듈 테스트 (최우선)

현재 Core 모듈(`hibernate-reactive-coroutines/`)은 테스트가 전혀 없습니다.

### 1.1 ReactiveSessionContext 테스트

**파일**: `ReactiveSessionContextTest.kt`

테스트 케이스:
- [ ] CoroutineContext Element 등록 및 조회
- [ ] TransactionMode 전환 (READ_ONLY ↔ READ_WRITE)
- [ ] remainingTimeout() 계산 정확성
- [ ] currentSessionOrNull() 헬퍼 동작
- [ ] currentContextOrNull() 헬퍼 동작
- [ ] 중첩 컨텍스트에서의 세션 전파

```kotlin
// 예시 테스트 구조
describe("ReactiveSessionContext") {
    context("CoroutineContext Element로 사용할 때") {
        it("세션을 코루틴 체인에 전파한다") { ... }
        it("읽기 전용 모드를 설정할 수 있다") { ... }
    }
    
    context("타임아웃 계산") {
        it("남은 시간을 정확히 계산한다") { ... }
        it("만료된 경우 0 이하를 반환한다") { ... }
    }
}
```

### 1.2 ReactiveSessionProvider 테스트

**파일**: `ReactiveSessionProviderTest.kt`

테스트 케이스:
- [ ] read() - 컨텍스트 있으면 세션 재사용
- [ ] read() - 컨텍스트 없으면 새 세션 생성
- [ ] write() - 정상 쓰기 동작
- [ ] write() - ReadOnly 모드에서 ReadOnlyTransactionException 발생
- [ ] 세션 재사용 vs 새 세션 판단 로직

```kotlin
describe("ReactiveSessionProvider") {
    context("read 헬퍼") {
        it("기존 컨텍스트의 세션을 재사용한다") { ... }
        it("컨텍스트가 없으면 새 세션을 생성한다") { ... }
    }
    
    context("write 헬퍼") {
        it("쓰기 작업을 수행한다") { ... }
        it("ReadOnly 모드에서 예외를 발생시킨다") { ... }
    }
}
```

### 1.3 ReactiveTransactionExecutor 테스트

**파일**: `ReactiveTransactionExecutorTest.kt`

테스트 케이스:
- [ ] transactional {} - 트랜잭션 시작/커밋
- [ ] transactional {} - 예외 시 롤백
- [ ] readOnly {} - 읽기 전용 세션 생성
- [ ] Vert.x Dispatcher 바인딩 검증
- [ ] 타임아웃 계산 (calculateEffectiveTimeout)
- [ ] 중첩 트랜잭션의 타임아웃 상속
- [ ] 스레드 안전성 검증

```kotlin
describe("ReactiveTransactionExecutor") {
    context("트랜잭션 경계") {
        it("성공 시 커밋한다") { ... }
        it("예외 시 롤백한다") { ... }
    }
    
    context("중첩 트랜잭션") {
        it("기존 트랜잭션에 참여한다") { ... }
        it("타임아웃을 상속받는다") { ... }
    }
}
```

---

## Phase 2: Edge Cases 및 에러 시나리오

### 2.1 트랜잭션 에러 시나리오

**파일**: `TransactionErrorScenariosTest.kt`

테스트 케이스:
- [ ] 부분 실패 후 전체 롤백 확인
- [ ] 중첩 트랜잭션 내부 예외의 외부 전파
- [ ] 세션 닫힘 상태에서 작업 시도
- [ ] 트랜잭션 타임아웃 초과
- [ ] 커밋 중 예외 발생
- [ ] flush 실패 시나리오

### 2.2 동시성 테스트 강화

**파일**: `AdvancedConcurrencyTest.kt`

현재 `ConcurrencyIntegrationTest`가 있지만 더 강화 필요:
- [ ] 100+ 동시 트랜잭션 처리
- [ ] 같은 엔티티 동시 수정 (Optimistic Lock)
- [ ] 데드락 감지 및 복구
- [ ] 커넥션 풀 고갈 시나리오
- [ ] Vert.x EventLoop 스레드 바인딩 검증

### 2.3 세션 라이프사이클 테스트

**파일**: `SessionLifecycleTest.kt`

테스트 케이스:
- [ ] 세션 생성/종료 사이클
- [ ] 세션 만료 후 재사용 시도
- [ ] 여러 스레드에서 같은 세션 접근 (금지됨)
- [ ] withContext 사용 시 세션 무효화 검증

---

## Phase 3: 쿼리 메서드 테스트 확장

Spring Data JPA의 `JpaQueryCreatorTests`를 참고하여 확장합니다.

### 3.1 복잡한 쿼리 메서드 파싱

**파일**: `ComplexQueryMethodParsingTest.kt`

현재 `PartTreeHqlBuilderTest`에 60+ 케이스가 있지만 추가 필요:
- [ ] 깊은 AND/OR 중첩 (3단계 이상)
- [ ] Between, In 키워드 (현재 미지원이면 명시적 에러)
- [ ] IgnoreCase 조합
- [ ] 프로퍼티 경로 탐색 (nested.property)
- [ ] 예약어 충돌 케이스

### 3.2 @Query 고급 기능

**파일**: `AdvancedQueryAnnotationTest.kt`

테스트 케이스:
- [ ] JOIN 쿼리 with 페이지네이션
- [ ] 서브쿼리 지원 검증
- [ ] 명시적 countQuery 정확도
- [ ] Native Query @Modifying 미지원 에러 메시지
- [ ] 파라미터 타입 불일치 에러

### 3.3 페이지네이션 Edge Cases

**파일**: `PaginationEdgeCasesTest.kt`

테스트 케이스:
- [ ] 빈 결과 페이지
- [ ] 마지막 페이지 (hasNext = false)
- [ ] 대용량 데이터 (1000+ rows) 페이징
- [ ] Sort 조합 복잡 케이스
- [ ] offset이 전체 개수보다 큰 경우

---

## Phase 4: Spring @Transactional 통합 심화

### 4.1 전파 옵션 테스트

**파일**: `TransactionPropagationTest.kt`

현재 `SpringTransactionalIntegrationTest`가 있지만 전파 옵션 테스트 부족:
- [ ] REQUIRED (기본) - 기존 트랜잭션 참여
- [ ] REQUIRES_NEW - 미지원 명시적 에러 검증
- [ ] SUPPORTS - 트랜잭션 없이 실행 가능
- [ ] NOT_SUPPORTED - 트랜잭션 일시 중단
- [ ] readOnly=true로 쓰기 시도 시 에러

### 4.2 롤백 규칙 테스트

**파일**: `TransactionRollbackRulesTest.kt`

테스트 케이스:
- [ ] RuntimeException 자동 롤백
- [ ] CheckedException 롤백 안 함 (기본)
- [ ] rollbackFor 지정 예외 롤백
- [ ] noRollbackFor 지정 예외 커밋
- [ ] 중첩 호출에서의 롤백 전파

---

## Phase 5: Repository 자동 구현 테스트

### 5.1 제네릭 및 상속 테스트

**파일**: `RepositoryInheritanceTest.kt`

Spring Data JPA의 `UserRepositoryTests` 참고:
- [ ] 제네릭 타입 정보 보존 검증
- [ ] 상속된 Repository 메서드 충돌
- [ ] 다중 인터페이스 상속
- [ ] 커스텀 구현체 오버라이드

### 5.2 Bean 등록 테스트 확장

**파일**: `RepositoryBeanRegistrationTest.kt`

테스트 케이스:
- [ ] basePackages 정확한 스캔
- [ ] excludeFilters 동작
- [ ] 중복 Repository 정의 에러
- [ ] Lazy 초기화 옵션
- [ ] 다중 EntityManagerFactory 환경 (미지원 시 에러)

---

## Phase 6: 테스트 인프라 개선

### 6.1 SQL 검증 유틸리티

Spring Data R2DBC의 `StatementRecorder` 패턴 도입:

```kotlin
// 생성된 SQL 캡처 및 검증
class HqlRecorder {
    fun getGeneratedQueries(): List<String>
    fun getLastQuery(): String
    fun assertQueryContains(substring: String)
}
```

### 6.2 테스트 데이터 격리 강화

현재 `AtomicInteger` 기반 격리를 개선:
- [ ] 테스트별 독립적인 데이터 세트
- [ ] 병렬 테스트 실행 안전성 보장
- [ ] 테스트 후 자동 정리

### 6.3 성능 벤치마크 추가

**파일**: `PerformanceBenchmarkTest.kt`

기본 성능 측정 (프로덕션 기준선):
- [ ] 단일 엔티티 CRUD 레이턴시
- [ ] 100개 엔티티 배치 저장
- [ ] 1000개 엔티티 페이징 조회
- [ ] 동시 10개 트랜잭션 처리량

---

## Phase 7: Boot 4 호환성 검증

### 7.1 Boot 4 전용 테스트

현재 Boot 4 모듈은 Boot 3 테스트를 복제하고 있음.
Boot 4 특화 기능 테스트 추가:
- [ ] Jakarta EE 10 호환성
- [ ] Virtual Thread 지원 (Java 21)
- [ ] 새로운 Spring Security 통합

---

## 우선순위 및 예상 작업량

| Phase | 우선순위 | 예상 테스트 수 | 복잡도 |
|-------|---------|-------------|-------|
| 1. Core 모듈 | **최상** | 20-30 | 중 |
| 2. Edge Cases | **상** | 30-40 | 상 |
| 3. 쿼리 메서드 확장 | 중 | 20-30 | 중 |
| 4. @Transactional 심화 | 중 | 15-20 | 중 |
| 5. Repository 자동 구현 | 중 | 10-15 | 중 |
| 6. 인프라 개선 | 하 | N/A | 상 |
| 7. Boot 4 호환성 | 하 | 5-10 | 하 |

---

## 참고한 Spring Data 테스트 패턴

### Spring Data JPA에서 참고

1. **Query Creator Tests**: Builder 패턴으로 쿼리 생성 검증
2. **DelegatingTransactionManager**: 트랜잭션 동작 인터셉트
3. **Sample Domain Model**: 64개 테스트 엔티티로 다양한 시나리오

### Spring Data R2DBC에서 참고

1. **StepVerifier**: 리액티브 스트림 테스트 패턴
2. **StatementRecorder**: SQL 캡처 및 검증
3. **ExternalDatabase**: 데이터베이스 추상화
4. **Coroutine Extensions**: runBlocking, Flow 테스트 패턴

---

## 성공 기준

프로덕션 준비 상태 증명을 위한 최소 기준:

1. **Core 모듈 커버리지 80%+**
2. **모든 public API에 대한 단위 테스트**
3. **에러 시나리오 포괄적 커버**
4. **동시성 안전성 검증 완료**
5. **Spring Data 호환 동작 검증**
