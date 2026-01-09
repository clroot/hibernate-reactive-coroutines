plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    `maven-publish`
}

allprojects {
    group = "com.github.clroot"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "maven-publish")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            freeCompilerArgs.add("-Xjsr305=strict")
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
}
