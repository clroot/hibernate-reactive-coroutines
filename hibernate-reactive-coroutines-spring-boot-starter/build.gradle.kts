plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.dependency.management)
    `java-test-fixtures`
}

dependencies {
    api(project(":hibernate-reactive-coroutines"))

    // Spring Boot
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.autoconfigure)

    // Spring Boot annotation processor
    annotationProcessor(libs.spring.boot.configuration.processor)

    // Test
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.extensions.spring)

    // TestFixtures
    testFixturesApi(libs.spring.boot.starter.test)
    testFixturesApi(libs.kotest.runner.junit5)
    testFixturesApi(libs.kotest.assertions.core)
    testFixturesApi(libs.kotest.extensions.spring)
    testFixturesApi(libs.testcontainers)
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesApi(libs.testcontainers.junit.jupiter)
    testFixturesApi(libs.vertx.pg.client)
    testFixturesApi(libs.postgresql)
    testFixturesApi(libs.scram.client)
}
