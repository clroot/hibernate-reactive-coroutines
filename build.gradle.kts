plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    `maven-publish`
    signing
}

allprojects {
    apply(plugin = "maven-publish")

    group = "io.clroot"
    version = "1.0.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        withSourcesJar()
        withJavadocJar()
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
        val signingKey = findProperty("signingKey") as String? ?: System.getenv("GPG_SIGNING_KEY")
        val signingPassword = findProperty("signingPassword") as String? ?: System.getenv("GPG_SIGNING_PASSWORD")

        if (signingKey != null && signingPassword != null) {
            useInMemoryPgpKeys(signingKey, signingPassword)
        }

        sign(extensions.getByType<PublishingExtension>().publications["maven"])
    }

    tasks.withType<Sign>().configureEach {
        onlyIf {
            val signingKey = findProperty("signingKey") as String? ?: System.getenv("GPG_SIGNING_KEY")
            signingKey != null
        }
    }
}
