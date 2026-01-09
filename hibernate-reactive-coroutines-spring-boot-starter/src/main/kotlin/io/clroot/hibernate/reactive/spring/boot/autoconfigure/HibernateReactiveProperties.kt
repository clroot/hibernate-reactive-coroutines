package io.clroot.hibernate.reactive.spring.boot.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Hibernate Reactive 전용 설정 프로퍼티.
 *
 * 대부분의 설정은 기존 Spring 프로퍼티를 그대로 사용합니다:
 * - `spring.datasource.*`: DB 연결 정보
 * - `spring.jpa.*`: JPA/Hibernate 설정
 *
 * 이 프로퍼티는 Hibernate Reactive 전용 설정만 포함합니다:
 *
 * ```yaml
 * kotlin:
 *   hibernate:
 *     reactive:
 *       pool-size: 10  # 커넥션 풀 사이즈
 * ```
 */
@ConfigurationProperties(prefix = "kotlin.hibernate.reactive")
data class HibernateReactiveProperties(
    /**
     * Hibernate Reactive 커넥션 풀 사이즈 (기본값: 10)
     *
     * 일반 JDBC의 HikariCP와 달리, Hibernate Reactive는 자체 커넥션 풀을 사용합니다.
     */
    val poolSize: Int = 10,
)
