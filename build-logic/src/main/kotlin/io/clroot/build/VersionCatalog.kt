package io.clroot.build

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/**
 * Resolves a library from the `libs` version catalog.
 *
 * Precompiled script plugins do not get the generated `libs.*` accessors, so convention plugins go
 * through the catalog by alias instead. An unknown alias fails at configuration time.
 */
fun Project.lib(alias: String): Provider<MinimalExternalModuleDependency> =
    extensions.getByType<VersionCatalogsExtension>()
        .named("libs")
        .findLibrary(alias)
        .orElseThrow { IllegalArgumentException("Version catalog 'libs' has no library '$alias'.") }
