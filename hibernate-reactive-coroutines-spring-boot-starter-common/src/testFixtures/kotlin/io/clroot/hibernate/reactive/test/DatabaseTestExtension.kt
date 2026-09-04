package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.test.container.PostgreSQLTestContainer
import io.kotest.core.extensions.SpecExtension
import io.kotest.core.spec.Spec

/**
 * Manages the Testcontainer lifecycle and schema isolation for Kotest specs.
 *
 * Each spec receives a unique PostgreSQL schema to prevent data collisions during
 * parallel execution. The schema is dropped when the spec completes.
 */
class DatabaseTestExtension : SpecExtension {
    override suspend fun intercept(
        spec: Spec,
        execute: suspend (Spec) -> Unit,
    ) {
        PostgreSQLTestContainer.start()

        val schemaName = generateSchemaName(spec)
        PostgreSQLTestContainer.createSchema(schemaName)
        PostgreSQLTestContainer.configureSystemProperties(schemaName)

        try {
            execute(spec)
        } finally {
            PostgreSQLTestContainer.dropSchema(schemaName)
        }
    }

    /**
     * Generates a unique schema name from the spec class name and a timestamp.
     *
     * PostgreSQL schema names may contain only lowercase letters, digits, and
     * underscores, and are limited to 63 characters.
     */
    private fun generateSchemaName(spec: Spec): String {
        val className = spec.javaClass.simpleName
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "_")
            .take(30)
        return "test_${className}_${System.nanoTime() % 1_000_000}"
    }
}
