package io.clroot.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipFile

/**
 * Fails when the published javadoc JAR carries no rendered documentation.
 *
 * The javadoc JAR is assembled from Dokka HTML rather than javadoc, so an empty archive is a silent
 * packaging failure that Maven Central would happily accept.
 */
abstract class VerifyJavadocJar : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val javadocJar: RegularFileProperty

    @TaskAction
    fun verify() {
        val archive = javadocJar.get().asFile
        val containsDocumentation = ZipFile(archive).use { zip ->
            zip.entries().asSequence().any { entry ->
                !entry.isDirectory && entry.name.endsWith(".html")
            }
        }
        if (!containsDocumentation) {
            throw GradleException("Javadoc JAR contains no Dokka HTML: $archive")
        }
    }
}
