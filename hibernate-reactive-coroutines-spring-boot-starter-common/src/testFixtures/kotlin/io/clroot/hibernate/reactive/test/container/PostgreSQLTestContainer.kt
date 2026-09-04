package io.clroot.hibernate.reactive.test.container

import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/**
 * Singleton PostgreSQL Testcontainer for integration tests.
 *
 * It is started on demand and supports schema isolation for parallel test execution.
 */
object PostgreSQLTestContainer {
    private const val POSTGRES_IMAGE = "postgres:16-alpine"
    private const val DATABASE_NAME = "khr_test"
    private const val USERNAME = "test"
    private const val PASSWORD = "test"

    val instance: PostgreSQLContainer by lazy {
        PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
            .apply {
                withDatabaseName(DATABASE_NAME)
                withUsername(USERNAME)
                withPassword(PASSWORD)
                withReuse(true)
            }
    }

    /**
     * Starts the container when necessary and returns its JDBC URL.
     */
    fun start(): String {
        if (!instance.isRunning) {
            instance.start()
        }
        return instance.jdbcUrl
    }

    /**
     * Stops the running container.
     */
    fun stop() {
        if (instance.isRunning) {
            instance.stop()
        }
    }

    /**
     * Creates a schema for test isolation.
     *
     * @param schemaName Schema name to create.
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
     * Drops an isolated test schema.
     *
     * @param schemaName Schema name to drop.
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
     * Configures Spring DataSource system properties for the container.
     *
     * @param schemaName Schema to use, or the default schema when null.
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

        // Isolated schemas require table creation because they start empty.
        if (schemaName != null) {
            System.setProperty("spring.jpa.hibernate.ddl-auto", "create")
        }
    }
}
