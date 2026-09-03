plugins {
    id("hrc.published")
}

description = "Framework-neutral repository query model and HQL compiler for Hibernate Reactive Coroutines"

dependencies {
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}
