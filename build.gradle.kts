import io.clroot.build.VerifyStarterSourceLayout
import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension

plugins {
    id("hrc.base")
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
    // No root publication, but nmcp hangs its all-publications entry points off the root plugin.
    `maven-publish`
}

val releaseVersion = providers.gradleProperty("releaseVersion")
val releaseTag = providers.gradleProperty("releaseTag")
val releaseVersionPattern = Regex("""^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z][0-9A-Za-z.-]*)?$""")

val starterProjects = listOf(
    project(":hibernate-reactive-coroutines-spring-boot-starter"),
    project(":hibernate-reactive-coroutines-spring-boot-starter-boot4"),
)

val coveredProjects = listOf(
    project(":hibernate-reactive-coroutines-blockhound"),
    project(":hibernate-reactive-coroutines-core"),
    project(":hibernate-reactive-coroutines-ktor"),
    project(":hibernate-reactive-coroutines-repository"),
    project(":hibernate-reactive-coroutines-spring-boot-starter"),
    project(":hibernate-reactive-coroutines-spring-boot-starter-boot4"),
)

coveredProjects.forEach { coveredProject ->
    coveredProject.pluginManager.apply("org.jetbrains.kotlinx.kover")
}

starterProjects.forEach { starterProject ->
    starterProject.extensions.configure<KoverProjectExtension> {
        currentProject {
            instrumentation {
                disabledForTestTasks.add("benchmark")
            }
        }
        reports {
            filters {
                excludes {
                    classes("io.clroot.hibernate.reactive.test.PerformanceBenchmarkTest*")
                }
            }
        }
    }
}

kover {
    reports {
        filters {
            excludes {
                classes("io.clroot.hibernate.reactive.test.PerformanceBenchmarkTest*")
            }
        }
        total {
            verify {
                rule("Minimum aggregate line coverage") {
                    minBound(85, CoverageUnit.LINE, AggregationType.COVERED_PERCENTAGE)
                }
                rule("Minimum aggregate branch coverage") {
                    minBound(65, CoverageUnit.BRANCH, AggregationType.COVERED_PERCENTAGE)
                }
            }
        }
    }
}

dependencies {
    coveredProjects.forEach { coveredProject ->
        kover(project(coveredProject.path))
    }
}

val verifyStarterMainSourceLayout = tasks.register<VerifyStarterSourceLayout>("verifyStarterMainSourceLayout") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Verifies that Boot starter main sources have a single owner."
    sharedSourceDirectory =
        layout.projectDirectory.dir("hibernate-reactive-coroutines-spring-boot-starter-common/src/main")
    starterSourceDirectories = starterProjects.map { it.layout.projectDirectory.dir("src/main") }
    versionSpecificPaths = setOf(
        "resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
    )
}

val verifyStarterTestSourceLayout = tasks.register<VerifyStarterSourceLayout>("verifyStarterTestSourceLayout") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Verifies that Boot starter test sources have a single owner."
    sharedSourceDirectory =
        layout.projectDirectory.dir("hibernate-reactive-coroutines-spring-boot-starter-common/src/test")
    starterSourceDirectories = starterProjects.map { it.layout.projectDirectory.dir("src/test") }
    versionSpecificPaths = emptySet()
}

val verifyStarterTestFixtureSourceLayout =
    tasks.register<VerifyStarterSourceLayout>("verifyStarterTestFixtureSourceLayout") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Verifies that Boot starter test fixtures have a single owner."
        sharedSourceDirectory =
            layout.projectDirectory.dir("hibernate-reactive-coroutines-spring-boot-starter-common/src/testFixtures")
        starterSourceDirectories = starterProjects.map { it.layout.projectDirectory.dir("src/testFixtures") }
        versionSpecificPaths = emptySet()
    }

val verifyStarterBenchmarkTestSourceLayout =
    tasks.register<VerifyStarterSourceLayout>("verifyStarterBenchmarkTestSourceLayout") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Verifies that Boot starter benchmark sources have a single owner."
        sharedSourceDirectory =
            layout.projectDirectory.dir("hibernate-reactive-coroutines-spring-boot-starter-common/src/benchmarkTest")
        starterSourceDirectories = starterProjects.map { it.layout.projectDirectory.dir("src/benchmarkTest") }
        versionSpecificPaths = emptySet()
    }

tasks.register("verifyStarterSourceLayout") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Verifies that all Boot starter source sets have a single owner."
    dependsOn(
        verifyStarterMainSourceLayout,
        verifyStarterTestSourceLayout,
        verifyStarterTestFixtureSourceLayout,
        verifyStarterBenchmarkTestSourceLayout,
    )
}

tasks.register("validateReleaseVersion") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Validates the release version and its optional source tag."

    val version = releaseVersion
    val tag = releaseTag

    doLast {
        val versionToPublish = version.orNull
            ?: throw GradleException(
                "releaseVersion is required for a Central release. " +
                    "Pass -PreleaseVersion=<version> or ORG_GRADLE_PROJECT_releaseVersion.",
            )

        if (!releaseVersionPattern.matches(versionToPublish)) {
            throw GradleException("Invalid releaseVersion '$versionToPublish'.")
        }

        val normalizedTag = tag.orNull?.takeIf(String::isNotBlank)?.removePrefix("v")
        if (normalizedTag != null && normalizedTag != versionToPublish) {
            throw GradleException(
                "releaseVersion '$versionToPublish' does not match releaseTag '${tag.get()}'.",
            )
        }
    }
}

tasks.register("validateReleaseSigning") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Validates that a Central release has complete in-memory PGP credentials."

    val signingKey = providers.gradleProperty("signingKey")
        .orElse(providers.environmentVariable("GPG_SIGNING_KEY"))
    val signingPassword = providers.gradleProperty("signingPassword")
        .orElse(providers.environmentVariable("GPG_SIGNING_PASSWORD"))

    doLast {
        val missingCredentials = buildList {
            if (signingKey.orNull.isNullOrBlank()) {
                add("signingKey or GPG_SIGNING_KEY")
            }
            if (signingPassword.orNull.isNullOrBlank()) {
                add("signingPassword or GPG_SIGNING_PASSWORD")
            }
        }
        if (missingCredentials.isNotEmpty()) {
            throw GradleException(
                "Central release signing credentials are incomplete. Missing: " +
                    missingCredentials.joinToString() + ".",
            )
        }
    }
}
