plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    `java-test-fixtures`
}

dependencies {
    api(project(":hibernate-reactive-coroutines-core"))
    api(platform("org.springframework.boot:spring-boot-dependencies:4.0.0"))

    // PostgreSQL SCRAM Authentication (required by Vert.x pg-client at runtime)
    runtimeOnly(libs.scram.client)

    // Vert.x SQL Client (compileOnly for SslAwareSqlClientPoolConfiguration, provided at runtime by hibernate-reactive)
    compileOnly(libs.vertx.sql.client)

    // Kotlin Reflect (for parameter name extraction in @Query)
    implementation(kotlin("reflect"))

    // Spring Boot 4 (versions managed by BOM)
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-autoconfigure")

    // Spring Transaction (version managed by Spring Boot 4 BOM)
    api("org.springframework:spring-tx")

    // Spring Data (version managed by Spring Boot 4 BOM)
    api("org.springframework.data:spring-data-commons")

    // Kotlin Coroutines Reactive (for Flow conversion)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.kotlinx.coroutines.reactor)

    // Mutiny-Reactor (for Uni/Mono conversion)
    implementation(libs.mutiny.reactor)

    // Spring Boot annotation processor
    annotationProcessor(platform("org.springframework.boot:spring-boot-dependencies:4.0.0"))
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.extensions.spring)
    testImplementation(libs.mockk)
    testImplementation(libs.scram.client)

    // TestFixtures
    testFixturesImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.0"))
    testFixturesApi("org.springframework:spring-tx")
    testFixturesApi("org.springframework.data:spring-data-commons")
    testFixturesApi("org.springframework.boot:spring-boot-starter-test")
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

// Exclude benchmark tests from normal test runs
tasks.test {
    useJUnitPlatform {
        excludeTags("benchmark")
    }
}

// Separate task for running benchmarks
tasks.register<Test>("benchmark") {
    useJUnitPlatform {
        includeTags("benchmark")
    }
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}
