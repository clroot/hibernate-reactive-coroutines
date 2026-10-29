import io.clroot.build.catalogVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Static analysis shared by every Kotlin module: ktlint for formatting, detekt for code smells.
 *
 * Both hook into `check`, so `./gradlew build` fails on a violation.
 */

plugins {
    id("org.jlleitschuh.gradle.ktlint")
    id("dev.detekt")
}

ktlint {
    version = catalogVersion("ktlint")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
    parallel = true
    // One baseline per project; regenerate with `./gradlew detektBaseline` only to accept existing findings.
    baseline = rootProject.layout.projectDirectory
        .file("config/detekt/baseline/${path.removePrefix(":").replace(':', '-')}.xml")
}

// detekt's default source is the conventional `src/*/kotlin` layout, which misses directories added
// to a source set elsewhere (the Boot starters compile a shared tree). Follow the source sets instead.
pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
    val kotlinSourceDirectories = extensions.getByType<KotlinJvmProjectExtension>().sourceSets
        .map { sourceSet -> sourceSet.kotlin.sourceDirectories }
    detekt {
        source.setFrom(kotlinSourceDirectories)
    }
}
