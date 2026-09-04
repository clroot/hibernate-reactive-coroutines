import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Kotlin library baseline: Java 21 target, explicit API, and a binary-compatibility gate.
 */

plugins {
    id("hrc.base")
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

kotlin {
    explicitApi()

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<KotlinCompile>().configureEach {
    // jvmTarget is inherited from the Java toolchain above.
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
        )
        javaParameters = true
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-Xshare:off")
}

tasks.named("check") {
    dependsOn("checkKotlinAbi")
}
