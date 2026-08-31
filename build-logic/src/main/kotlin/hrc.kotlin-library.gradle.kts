import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Kotlin library baseline: Java 17 target, explicit API, and a binary-compatibility gate.
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
        languageVersion = JavaLanguageVersion.of(17)
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<KotlinCompile>().configureEach {
    // jvmTarget is inherited from the Java toolchain above.
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-Xannotation-default-target=param-property",
        )
        javaParameters = true
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.named("check") {
    dependsOn("checkKotlinAbi")
}
