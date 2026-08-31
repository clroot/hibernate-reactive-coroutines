import io.clroot.build.VerifyJavadocJar

/**
 * Maven Central publication: Dokka-backed javadoc JAR, POM metadata, and conditional PGP signing.
 */

plugins {
    id("hrc.kotlin-library")
    id("org.jetbrains.dokka")
    `maven-publish`
    signing
}

val signingKey = providers.gradleProperty("signingKey")
    .orElse(providers.environmentVariable("GPG_SIGNING_KEY"))
val signingPassword = providers.gradleProperty("signingPassword")
    .orElse(providers.environmentVariable("GPG_SIGNING_PASSWORD"))
val hasSigningCredentials = signingKey.orNull?.isNotBlank() == true &&
    signingPassword.orNull?.isNotBlank() == true

val dokkaHtml = tasks.named("dokkaGeneratePublicationHtml")

// Publish Dokka HTML in place of javadoc; `from(Provider)` also carries the task dependency.
val javadocJarTask = tasks.named<Jar>("javadocJar") {
    from(dokkaHtml.map { it.outputs.files })
}

val verifyJavadocJar = tasks.register<VerifyJavadocJar>("verifyJavadocJar") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Verifies that the published javadoc JAR contains Dokka HTML."
    javadocJar = javadocJarTask.flatMap { it.archiveFile }
}

tasks.named("check") {
    dependsOn(verifyJavadocJar)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name = project.name
                description = "Spring Data JPA-like convenience for Hibernate Reactive + Kotlin Coroutines"
                url = "https://github.com/clroot/hibernate-reactive-coroutines"

                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }

                developers {
                    developer {
                        id = "clroot"
                        name = "clroot"
                        url = "https://github.com/clroot"
                    }
                }

                scm {
                    connection = "scm:git:git://github.com/clroot/hibernate-reactive-coroutines.git"
                    developerConnection = "scm:git:ssh://github.com/clroot/hibernate-reactive-coroutines.git"
                    url = "https://github.com/clroot/hibernate-reactive-coroutines"
                }
            }
        }
    }
}

signing {
    // Both guards are load-bearing: the key can only be read when it exists, and the Sign tasks must
    // be skipped outright on local or snapshot builds that have no credentials at all.
    if (hasSigningCredentials) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.get())
    }
    sign(publishing.publications["maven"])
}

tasks.withType<Sign>().configureEach {
    onlyIf("complete in-memory PGP credentials are configured") { hasSigningCredentials }
}
