package io.clroot.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when generated publication metadata exposes test-only coordinates or variants.
 *
 * Test fixtures are consumed inside this build but must never reach Maven Central consumers, so the
 * generated POM and Gradle module metadata are scanned for the markers those leaks would produce.
 */
abstract class VerifyPublicationMetadata : DefaultTask() {

    /** Generated `pom-default.xml` and `module.json` for the published publication. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val metadataFiles: ConfigurableFileCollection

    /** Substrings that may never appear in published metadata. */
    @get:Input
    abstract val forbiddenMarkers: SetProperty<String>

    @TaskAction
    fun verify() {
        val markers = forbiddenMarkers.get()
        metadataFiles.forEach { metadataFile ->
            val metadata = metadataFile.readText()
            markers.firstOrNull(metadata::contains)?.let { marker ->
                throw GradleException("${metadataFile.name} exposes test-fixture marker '$marker'.")
            }
        }
    }
}
