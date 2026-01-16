plugins {
    `java-library`
}

dependencies {
    // Hibernate Reactive
    api(libs.hibernate.reactive.core)

    // Mutiny Kotlin
    api(libs.mutiny.kotlin)

    // Vert.x
    api(libs.vertx.core)
    api(libs.vertx.lang.kotlin.coroutines)

    // Kotlin Coroutines
    api(libs.kotlinx.coroutines.core)

    // Test
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
}
