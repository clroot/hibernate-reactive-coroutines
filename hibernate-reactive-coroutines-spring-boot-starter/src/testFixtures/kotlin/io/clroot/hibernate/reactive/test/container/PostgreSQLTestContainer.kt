package io.clroot.hibernate.reactive.test.container

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * PostgreSQL TestContainer 싱글톤.
 *
 * 테스트 실행 시 PostgreSQL 컨테이너를 시작하고,
 * Spring DataSource 프로퍼티를 자동으로 설정합니다.
 */
object PostgreSQLTestContainer {
    private const val POSTGRES_IMAGE = "postgres:16-alpine"
    private const val DATABASE_NAME = "khr_test"
    private const val USERNAME = "test"
    private const val PASSWORD = "test"

    val instance: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
            .apply {
                withDatabaseName(DATABASE_NAME)
                withUsername(USERNAME)
                withPassword(PASSWORD)
                withReuse(true)
            }
    }

    /**
     * 컨테이너를 시작하고 JDBC URL을 반환합니다.
     */
    fun start(): String {
        if (!instance.isRunning) {
            instance.start()
        }
        return instance.jdbcUrl
    }

    /**
     * 컨테이너를 정지합니다.
     */
    fun stop() {
        if (instance.isRunning) {
            instance.stop()
        }
    }

    /**
     * Spring DataSource 시스템 프로퍼티를 설정합니다.
     */
    fun configureSystemProperties() {
        System.setProperty("spring.datasource.url", instance.jdbcUrl)
        System.setProperty("spring.datasource.username", instance.username)
        System.setProperty("spring.datasource.password", instance.password)
    }
}
