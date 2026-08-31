import io.clroot.build.VerifyStarterSourceLayout

plugins {
    id("hrc.base")
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

tasks.register("verifyStarterSourceLayout") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Verifies that all Boot starter source sets have a single owner."
    dependsOn(
        verifyStarterMainSourceLayout,
        verifyStarterTestSourceLayout,
        verifyStarterTestFixtureSourceLayout,
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
