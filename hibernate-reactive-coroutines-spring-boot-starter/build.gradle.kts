plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    id("org.springframework.boot") version "3.4.13"
    id("io.spring.dependency-management") version "1.1.7"
    `java-test-fixtures`
}

// Library module - disable bootJar, enable plain jar without classifier
tasks.bootJar { enabled = false }
tasks.jar {
    enabled = true
    archiveClassifier.set("")
}

// Override versions to match hibernate-reactive-core requirement
extra["hibernate.version"] = "7.1.0.Final"
extra["jakarta-persistence.version"] = "3.2.0"

dependencies {
    api(project(":hibernate-reactive-coroutines"))

    // PostgreSQL SCRAM Authentication (required by Vert.x pg-client at runtime)
    runtimeOnly(libs.scram.client)

    // Vert.x SQL Client (compileOnly for SslAwareSqlClientPoolConfiguration, provided at runtime by hibernate-reactive)
    compileOnly(libs.vertx.sql.client)

    // Kotlin Reflect (for parameter name extraction in @Query)
    implementation(kotlin("reflect"))

    // Spring Boot (versions managed by BOM)
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework:spring-tx")
    implementation("org.springframework.data:spring-data-commons")

    // Kotlin Coroutines Reactive (for Flow conversion)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.kotlinx.coroutines.reactor)

    // Mutiny-Reactor (for Uni/Mono conversion)
    implementation(libs.mutiny.reactor)

    // Spring Boot annotation processor
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.extensions.spring)
    testImplementation(libs.mockk)
    testImplementation(libs.scram.client)

    // TestFixtures
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

// Test configuration
tasks.test {
    useJUnitPlatform {
        excludeTags("benchmark") // 일반 테스트에서 벤치마크 제외
    }
}

// Benchmark task
tasks.register<Test>("benchmark") {
    description = "Run performance benchmark tests"
    group = "verification"

    useJUnitPlatform {
        includeTags("benchmark")
    }

    testLogging {
        showStandardStreams = true // 벤치마크 결과 출력
        events("passed", "skipped", "failed")
    }
}
