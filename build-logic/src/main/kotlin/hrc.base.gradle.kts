import io.clroot.build.CENTRAL_RELEASE_TASK_PATTERN

/**
 * Identity and release gating shared by the root build and every module.
 */

group = "io.clroot"

// Gradle needs `version` as a resolved value, so the provider chain is unwrapped exactly once here.
version = providers.gradleProperty("releaseVersion").orElse("1.0.0-SNAPSHOT").get()

repositories {
    mavenCentral()
}

tasks.matching { CENTRAL_RELEASE_TASK_PATTERN.matches(it.name) }.configureEach {
    dependsOn(
        rootProject.tasks.named("validateReleaseVersion"),
        rootProject.tasks.named("validateReleaseSigning"),
    )
}
