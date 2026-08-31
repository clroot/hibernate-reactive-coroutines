plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.spring.gradle.plugin)
    implementation(libs.kotlin.jpa.gradle.plugin)
    implementation(libs.dokka.gradle.plugin)
}
