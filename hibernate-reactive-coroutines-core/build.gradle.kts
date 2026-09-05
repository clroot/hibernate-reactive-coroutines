plugins {
    id("hrc.published")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=io.clroot.hibernate.reactive.InternalHrcApi")
    }
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
    testImplementation(libs.kotlinx.coroutines.test)
}
