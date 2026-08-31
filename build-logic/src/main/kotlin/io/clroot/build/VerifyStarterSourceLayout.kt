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
 * Fails when Boot starter modules copy a path owned by a shared source tree or each other.
 *
 * The starters deliberately cross-compile shared source trees against different Spring Boot
 * platforms. Shared main, test, and test-fixture trees must remain the single owner of every common
 * file, while each starter's own tree may contain only version-specific paths.
 */
abstract class VerifyStarterSourceLayout : DefaultTask() {

    /** Source tree shared by every starter; the single owner of the cross-compiled source set. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sharedSourceDirectory: DirectoryProperty

    /** Each starter's corresponding source directory, which may only hold version-specific files. */
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

        val sharedRoot = sharedSourceDirectory.get().asFile
        val sharedPaths = relativeFilePaths(sharedRoot)
        val starterPathSets = sourceRoots.map(::relativeFilePaths)
        val allowedPaths = versionSpecificPaths.get()

        val copiedFromShared = starterPathSets
            .flatMap { it.intersect(sharedPaths) }
            .toSet()
        val copiedBetweenStarters = starterPathSets
            .reduce(Set<String>::intersect)
        val duplicatedPaths = (copiedFromShared + copiedBetweenStarters)
            .minus(allowedPaths)
            .sorted()

        if (duplicatedPaths.isNotEmpty()) {
            throw GradleException(
                "Boot starter sources must have a single owner. " +
                    "Move common paths to $sharedRoot or declare a version-specific path:\n" +
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
