plugins {
    id("hrc.published")
}

description = "Framework-neutral repository query compiler and runtime for Hibernate Reactive Coroutines"

dependencies {
    api(project(":hibernate-reactive-coroutines-core"))
    api(libs.jakarta.data.api)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
}
