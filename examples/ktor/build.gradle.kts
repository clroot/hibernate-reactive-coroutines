plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.jpa")
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":hibernate-reactive-coroutines-ktor"))
    implementation(libs.ktor.server.netty)
    runtimeOnly(libs.vertx.pg.client)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.scram.client)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "io.clroot.examples.ktor.KtorExampleKt"
}
