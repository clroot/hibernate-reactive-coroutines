import java.net.URLClassLoader

plugins {
    id("hrc.published")
}

description = "Ktor application plugin for Hibernate Reactive coroutine repositories"

dependencies {
    api(project(":hibernate-reactive-coroutines-repository"))
    api(libs.ktor.server.core)

    // Optional: basic plugin use relies only on ktor-server-core.
    compileOnly(libs.ktor.server.di)

    testImplementation(libs.ktor.server.di)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.vertx.pg.client)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.scram.client)
}

val verifySpringFreeRuntimeClasspath = tasks.register("verifySpringFreeRuntimeClasspath") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Verifies that the Ktor integration has no Spring runtime dependencies."

    doLast {
        val springArtifacts = configurations.runtimeClasspath.get()
            .resolvedConfiguration
            .resolvedArtifacts
            .filter { artifact ->
                artifact.moduleVersion.id.group.startsWith("org.springframework")
            }
            .map { artifact -> artifact.moduleVersion.id.toString() }
            .sorted()

        check(springArtifacts.isEmpty()) {
            "Ktor runtime classpath must not contain Spring artifacts: ${springArtifacts.joinToString()}"
        }
    }
}

val verifyOptionalKtorDi = tasks.register("verifyOptionalKtorDi") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Verifies that basic Ktor integration does not require ktor-server-di."
    dependsOn(tasks.named("classes"))

    doLast {
        val ktorDiArtifacts = configurations.runtimeClasspath.get()
            .resolvedConfiguration
            .resolvedArtifacts
            .filter { artifact ->
                artifact.moduleVersion.id.group == "io.ktor" &&
                    artifact.name.startsWith("ktor-server-di")
            }
            .map { artifact -> artifact.moduleVersion.id.toString() }
            .sorted()

        check(ktorDiArtifacts.isEmpty()) {
            "ktor-server-di must remain optional: ${ktorDiArtifacts.joinToString()}"
        }

        val runtimeFiles = sourceSets.main.get().output.files +
            configurations.runtimeClasspath.get().files
        URLClassLoader(
            runtimeFiles.map { file -> file.toURI().toURL() }.toTypedArray(),
            ClassLoader.getPlatformClassLoader(),
        ).use { classLoader ->
            Class.forName(
                "io.clroot.hibernate.reactive.ktor.HibernateReactiveKt",
                true,
                classLoader,
            )
        }
    }
}

tasks.named("check") {
    dependsOn(verifySpringFreeRuntimeClasspath, verifyOptionalKtorDi)
}
