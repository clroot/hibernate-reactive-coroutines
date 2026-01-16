package io.clroot.hibernate.reactive.test.container

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/**
 * PostgreSQL TestContainer 싱글톤.
 *
 * 테스트 실행 시 PostgreSQL 컨테이너를 시작하고,
 * Spring DataSource 프로퍼티를 자동으로 설정합니다.
 *
 * 스키마 격리를 통해 병렬 테스트 실행을 지원합니다.
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
     * 스키마를 생성합니다.
     *
     * @param schemaName 생성할 스키마 이름
     */
    fun createSchema(schemaName: String) {
        DriverManager.getConnection(
            instance.jdbcUrl,
            instance.username,
            instance.password,
        ).use { conn ->
            conn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS $schemaName")
        }
    }

    /**
     * 스키마를 삭제합니다.
     *
     * @param schemaName 삭제할 스키마 이름
     */
    fun dropSchema(schemaName: String) {
        DriverManager.getConnection(
            instance.jdbcUrl,
            instance.username,
            instance.password,
        ).use { conn ->
            conn.createStatement().execute("DROP SCHEMA IF EXISTS $schemaName CASCADE")
        }
    }

    /**
     * Spring DataSource 시스템 프로퍼티를 설정합니다.
     *
     * @param schemaName 사용할 스키마 이름 (null이면 기본 스키마 사용)
     */
    fun configureSystemProperties(schemaName: String? = null) {
        val baseUrl = instance.jdbcUrl
        val url = if (schemaName != null) {
            if (baseUrl.contains("?")) "$baseUrl&currentSchema=$schemaName"
            else "$baseUrl?currentSchema=$schemaName"
        } else {
            baseUrl
        }

        System.setProperty("spring.datasource.url", url)
        System.setProperty("spring.datasource.username", instance.username)
        System.setProperty("spring.datasource.password", instance.password)

        // 스키마 격리 시 DDL Auto를 create로 설정하여 테이블 자동 생성
        if (schemaName != null) {
            System.setProperty("spring.jpa.hibernate.ddl-auto", "create")
        }
    }
}
