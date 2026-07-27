plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.dokka) apply false
    `maven-publish`
    signing
}

val releaseVersion = providers.gradleProperty("releaseVersion")
val releaseTag = providers.gradleProperty("releaseTag")
val effectiveVersion = releaseVersion.orElse("1.0.0-SNAPSHOT")
val signingKey = providers.gradleProperty("signingKey")
    .orElse(providers.environmentVariable("GPG_SIGNING_KEY"))
val signingPassword = providers.gradleProperty("signingPassword")
    .orElse(providers.environmentVariable("GPG_SIGNING_PASSWORD"))
val hasSigningKey = signingKey.orNull?.isNotBlank() == true
val hasSigningPassword = signingPassword.orNull?.isNotBlank() == true
val hasSigningCredentials = hasSigningKey && hasSigningPassword
val releaseVersionPattern = Regex("""^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z][0-9A-Za-z.-]*)?$""")
val centralReleaseTaskNames = setOf(
    "publishAllPublicationsToCentralPortal",
    "publishAllPublicationsToNmcpRepository",
    "publishMavenPublicationToNmcpRepository",
    "nmcpPublishAllPublicationsToCentralPortal",
    "nmcpPublishAggregationToCentralPortal",
    "publishAggregationToCentralPortal",
)
val starterProjectNames = setOf(
    "hibernate-reactive-coroutines-spring-boot-starter",
    "hibernate-reactive-coroutines-spring-boot-starter-boot4",
)
val sharedStarterMainDirectory = layout.projectDirectory.dir(
    "hibernate-reactive-coroutines-spring-boot-starter-common/src/main",
)
val versionSpecificStarterPaths = setOf(
    "resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
)

val verifyStarterSourceLayout by tasks.registering {
    group = "verification"
    description = "Verifies that Boot starter production sources have a single owner."

    val starterSourceDirectories = starterProjectNames.map { projectName ->
        layout.projectDirectory.dir("$projectName/src/main")
    }
    inputs.dir(sharedStarterMainDirectory)
    inputs.files(starterSourceDirectories)
    inputs.property(
        "sharedStarterMainDirectoryPath",
        sharedStarterMainDirectory.asFile.absolutePath,
    )
    inputs.property(
        "starterSourceDirectoryPaths",
        starterSourceDirectories.map { it.asFile.absolutePath },
    )
    inputs.property(
        "versionSpecificStarterPaths",
        versionSpecificStarterPaths.sorted(),
    )

    doLast {
        val sharedSourceDirectory = File(
            inputs.properties.getValue("sharedStarterMainDirectoryPath") as String,
        )
        val starterSourceDirectoryFiles =
            (inputs.properties.getValue("starterSourceDirectoryPaths") as List<*>)
                .map { File(it as String) }
        val allowedVersionSpecificPaths =
            (inputs.properties.getValue("versionSpecificStarterPaths") as List<*>)
                .map { it as String }
                .toSet()

        if (!sharedSourceDirectory.isDirectory) {
            throw GradleException(
                "Shared starter source directory does not exist: " +
                    sharedSourceDirectory,
            )
        }

        val relativePathsByProject = starterSourceDirectoryFiles.associate { sourceRoot ->
            sourceRoot.parentFile.parentFile.name to sourceRoot
                .walkTopDown()
                .filter(File::isFile)
                .map { it.relativeTo(sourceRoot).invariantSeparatorsPath }
                .toSet()
        }
        val duplicatedPaths = relativePathsByProject.values
            .reduce(Set<String>::intersect)
            .minus(allowedVersionSpecificPaths)
            .sorted()

        if (duplicatedPaths.isNotEmpty()) {
            throw GradleException(
                "Boot starter production sources must not be copied between modules:\n" +
                    duplicatedPaths.joinToString(separator = "\n") { " - $it" },
            )
        }
    }
}

val validateReleaseVersion by tasks.registering {
    group = "verification"
    description = "Validates the release version and its optional source tag."

    doLast {
        val versionToPublish = releaseVersion.orNull
            ?: throw GradleException(
                "releaseVersion is required for a Central release. " +
                    "Pass -PreleaseVersion=<version> or ORG_GRADLE_PROJECT_releaseVersion.",
            )

        if (!releaseVersionPattern.matches(versionToPublish)) {
            throw GradleException("Invalid releaseVersion '$versionToPublish'.")
        }

        releaseTag.orNull
            ?.takeIf { it.isNotBlank() }
            ?.removePrefix("v")
            ?.let { normalizedTag ->
                if (normalizedTag != versionToPublish) {
                    throw GradleException(
                        "releaseVersion '$versionToPublish' does not match releaseTag '${releaseTag.get()}'.",
                    )
                }
            }
    }
}

val validateReleaseSigning by tasks.registering {
    group = "verification"
    description = "Validates that a Central release has complete in-memory PGP credentials."
    inputs.property("hasSigningKey", hasSigningKey)
    inputs.property("hasSigningPassword", hasSigningPassword)

    doLast(Action<Task> {
        val taskHasSigningKey = inputs.properties["hasSigningKey"] as Boolean
        val taskHasSigningPassword = inputs.properties["hasSigningPassword"] as Boolean
        val missingCredentials = buildList {
            if (!taskHasSigningKey) {
                add("signingKey or GPG_SIGNING_KEY")
            }
            if (!taskHasSigningPassword) {
                add("signingPassword or GPG_SIGNING_PASSWORD")
            }
        }
        if (missingCredentials.isNotEmpty()) {
            throw GradleException(
                "Central release signing credentials are incomplete. Missing: " +
                    missingCredentials.joinToString() + ".",
            )
        }
    })
}

allprojects {
    apply(plugin = "maven-publish")

    group = "io.clroot"
    version = effectiveVersion.get()

    repositories {
        mavenCentral()
    }

    tasks.matching { it.name in centralReleaseTaskNames }.configureEach {
        dependsOn(
            rootProject.tasks.named("validateReleaseVersion"),
            rootProject.tasks.named("validateReleaseSigning"),
        )
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.dokka")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    if (name in starterProjectNames) {
        configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            sourceSets.named("main") {
                kotlin.srcDir(sharedStarterMainDirectory.dir("kotlin"))
            }
        }
        configure<SourceSetContainer> {
            named("main") {
                resources.srcDir(sharedStarterMainDirectory.dir("resources"))
            }
        }
        tasks.named("check") {
            dependsOn(rootProject.tasks.named("verifyStarterSourceLayout"))
        }
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
        withSourcesJar()
        withJavadocJar()
    }

    pluginManager.withPlugin("java-test-fixtures") {
        val javaComponent = components["java"] as org.gradle.api.component.AdhocComponentWithVariants
        javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) {
            skip()
        }
        javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) {
            skip()
        }

        val verifyPublicationMetadata by tasks.registering {
            group = "verification"
            description = "Verifies that test fixtures do not leak into published metadata."
            dependsOn(
                "generatePomFileForMavenPublication",
                "generateMetadataFileForMavenPublication",
            )
            inputs.files(
                layout.buildDirectory.file("publications/maven/pom-default.xml"),
                layout.buildDirectory.file("publications/maven/module.json"),
            )

            doLast(Action<Task> {
                val leakedMarkers = listOf(
                    "testFixturesApiElements",
                    "testFixturesRuntimeElements",
                    "-test-fixtures.jar",
                    "spring-boot-starter-test",
                    "io.kotest",
                    "org.testcontainers",
                )
                inputs.files.forEach { metadataFile ->
                    val metadata = metadataFile.readText()
                    leakedMarkers.firstOrNull(metadata::contains)?.let { marker ->
                        throw GradleException(
                            "${metadataFile.name} exposes test-fixture marker '$marker'.",
                        )
                    }
                }
            })
        }

        tasks.named("check") {
            dependsOn(verifyPublicationMetadata)
        }
    }

    val dokkaHtml = tasks.named("dokkaGeneratePublicationHtml")
    val javadocJar = tasks.named<Jar>("javadocJar") {
        dependsOn(dokkaHtml)
        from(dokkaHtml.map { it.outputs.files })
    }
    val verifyJavadocJar by tasks.registering {
        group = "verification"
        description = "Verifies that the published javadoc JAR contains Dokka HTML."
        dependsOn(javadocJar)
        inputs.file(javadocJar.flatMap { it.archiveFile })

        doLast(Action<Task> {
            val archive = inputs.files.singleFile
            val containsDocumentation = java.util.zip.ZipFile(archive).use { zip ->
                zip.entries().asSequence().any { entry ->
                    !entry.isDirectory && entry.name.endsWith(".html")
                }
            }
            if (!containsDocumentation) {
                throw GradleException("$path found an empty javadoc JAR: $archive")
            }
        })
    }

    tasks.named("check") {
        dependsOn(verifyJavadocJar)
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-Xjsr305=strict",
                "-Xannotation-default-target=param-property",
            )
            javaParameters.set(true)
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])

                pom {
                    name.set(project.name)
                    description.set("Spring Data JPA-like convenience for Hibernate Reactive + Kotlin Coroutines")
                    url.set("https://github.com/clroot/hibernate-reactive-coroutines")

                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }

                    developers {
                        developer {
                            id.set("clroot")
                            name.set("clroot")
                            url.set("https://github.com/clroot")
                        }
                    }

                    scm {
                        connection.set("scm:git:git://github.com/clroot/hibernate-reactive-coroutines.git")
                        developerConnection.set("scm:git:ssh://github.com/clroot/hibernate-reactive-coroutines.git")
                        url.set("https://github.com/clroot/hibernate-reactive-coroutines")
                    }
                }
            }
        }
    }

    configure<SigningExtension> {
        if (hasSigningCredentials) {
            useInMemoryPgpKeys(signingKey.get(), signingPassword.get())
        }

        sign(extensions.getByType<PublishingExtension>().publications["maven"])
    }

    tasks.withType<Sign>().configureEach {
        onlyIf("complete in-memory PGP credentials are configured") {
            hasSigningCredentials
        }
    }
}
