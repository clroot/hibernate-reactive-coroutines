plugins {
    id("hrc.published")
}

dependencies {
    // BlockHound is the user-facing API (`BlockHound.install()`), so it rides along transitively.
    api(libs.blockhound)

    // VertxThread is referenced by the non-blocking thread predicate.
    implementation(libs.vertx.core)

    testImplementation(project(":hibernate-reactive-coroutines-core"))
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    // Registers the kotlinx-coroutines BlockHound integration for coroutine-internal allowances.
    testImplementation(libs.kotlinx.coroutines.debug)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.vertx.pg.client)
    testRuntimeOnly(libs.scram.client)
    testRuntimeOnly(libs.postgresql)
}

tasks.withType<Test>().configureEach {
    // BlockHound instruments blocking JDK methods at runtime; JDK 13+ needs both flags for the
    // self-attaching ByteBuddy agent to redefine them.
    jvmArgs(
        "-XX:+AllowRedefinitionToAddDeleteMethods",
        "-Djdk.attach.allowAttachSelf=true",
    )
}
