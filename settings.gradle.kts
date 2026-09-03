pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("com.gradleup.nmcp.settings") version "1.4.3"
}

rootProject.name = "hibernate-reactive-coroutines"

include(
    "hibernate-reactive-coroutines-core",
    "hibernate-reactive-coroutines-repository",
    "hibernate-reactive-coroutines-ktor",
    "hibernate-reactive-coroutines-blockhound",
    "hibernate-reactive-coroutines-spring-boot-starter-common",
    "hibernate-reactive-coroutines-spring-boot-starter",
    "hibernate-reactive-coroutines-spring-boot-starter-boot4",
)

nmcpSettings {
    centralPortal {
        username = providers.environmentVariable("SONATYPE_USERNAME").orNull
            ?: providers.gradleProperty("sonatypeUsername").orNull
        password = providers.environmentVariable("SONATYPE_PASSWORD").orNull
            ?: providers.gradleProperty("sonatypePassword").orNull
        publishingType = "AUTOMATIC"
    }
}
