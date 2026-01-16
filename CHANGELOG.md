# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2026-01-14

### Added

- **Spring Boot 4.0 지원**
  - `hibernate-reactive-coroutines-spring-boot-starter-boot4` 모듈 추가
  - Spring Framework 7, Jakarta EE 11 호환
  - 기존 Boot 3.x starter와 동일한 기능 제공

### Changed

- **Kotlin 2.2.0 업그레이드**
  - Spring Boot 4.0 요구사항 충족
  - JSpecify null-safety 호환성 개선

### Notes

- Spring Boot 3.x 사용자: `hibernate-reactive-coroutines-spring-boot-starter` 사용
- Spring Boot 4.x 사용자: `hibernate-reactive-coroutines-spring-boot-starter-boot4` 사용

## [1.0.0] - 2026-01-12

### Added

- **Repository 인터페이스 자동 구현** (#1)
  - `CoroutineCrudRepository` 상속으로 CRUD 메서드 자동 구현
  - `save`, `findById`, `findAll`, `deleteById`, `count`, `existsById` 등 지원
  - Spring Bean 자동 등록

- **쿼리 메서드 자동 생성** (#2)
  - Spring Data JPA 스타일 메서드 이름 기반 쿼리 생성
  - `findBy`, `existsBy`, `countBy`, `deleteBy` 접두사 지원
  - `And`, `Or`, `Between`, `Like`, `In`, `OrderBy` 등 키워드 지원

- **Pagination 지원** (#3)
  - `Page<T>`, `Slice<T>` 반환 타입 지원
  - `Pageable`, `Sort` 파라미터 지원
  - 메서드 이름 기반 정렬 (`OrderByNameDesc`)

- **@Query 어노테이션 지원** (#4)
  - JPQL 쿼리 직접 작성
  - Named Parameter (`:name`) 바인딩
  - Positional Parameter (`?1`) 바인딩
  - `@Param` 어노테이션으로 명시적 파라미터 이름 지정
  - `@Modifying`으로 UPDATE/DELETE 쿼리 지원
  - Page/Slice 반환 시 `countQuery` 자동 생성 또는 명시적 지정

### Notes

- Hibernate Reactive Mutiny API 제약으로 네이티브 `@Modifying` 쿼리는 미지원
- Kotlin 파라미터 이름 자동 추출을 위해 `javaParameters` 컴파일러 옵션 활성화 필요

## [0.2.2] - 2026-01-12

- Pagination 기능 안정화

## [0.2.1] - 2026-01-12

- 쿼리 메서드 버그 수정

## [0.2.0] - 2026-01-12

- 쿼리 메서드 자동 생성 기능 추가

## [0.1.0] - 2026-01-12

- 초기 릴리즈
- Repository 인터페이스 자동 구현
- 기본 CRUD 메서드 지원
