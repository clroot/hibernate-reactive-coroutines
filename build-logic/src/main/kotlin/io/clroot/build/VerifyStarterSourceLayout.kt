package io.clroot.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Fails when two Boot starter modules physically own the same production source path.
 *
 * The starters deliberately cross-compile one shared source tree against different Spring Boot
 * platforms, so the shared tree must stay the single owner of every file it provides. A path that
 * appears in more than one starter's own `src/main` is a copy that will silently drift, except for
 * the explicitly allowed version-specific descriptors.
 */
abstract class VerifyStarterSourceLayout : DefaultTask() {

    /** Source tree shared by every starter; the single owner of the cross-compiled production code. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sharedSourceDirectory: DirectoryProperty

    /** Each starter's own `src/main`, which may only hold version-specific files. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val starterSourceDirectories: ListProperty<Directory>

    /** Paths allowed to exist in more than one starter because they are version-specific. */
    @get:Input
    abstract val versionSpecificPaths: SetProperty<String>

    @TaskAction
    fun verify() {
        val sourceRoots = starterSourceDirectories.get().map(Directory::getAsFile)
        if (sourceRoots.size < 2) {
            throw GradleException(
                "Expected at least two starter source directories to compare, got $sourceRoots.",
            )
        }

        val duplicatedPaths = sourceRoots
            .map(::relativeFilePaths)
            .reduce(Set<String>::intersect)
            .minus(versionSpecificPaths.get())
            .sorted()

        if (duplicatedPaths.isNotEmpty()) {
            throw GradleException(
                "Boot starter production sources must not be copied between modules. " +
                    "Move these to ${sharedSourceDirectory.get().asFile}:\n" +
                    duplicatedPaths.joinToString(separator = "\n") { " - $it" },
            )
        }
    }

    private fun relativeFilePaths(sourceRoot: File): Set<String> =
        sourceRoot.walkTopDown()
            .filter(File::isFile)
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath }
            .toSet()
}
