plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlin.plugin.jpa")
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":hibernate-reactive-coroutines-spring-boot-starter-boot4"))
    implementation("org.springframework.boot:spring-boot-starter-web")
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
    mainClass = "io.clroot.examples.springboot.SpringBootExampleKt"
}
