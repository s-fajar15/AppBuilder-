package dev.appbuilder

import java.io.File

/**
 * Checks done before Gradle starts, so a project that cannot build stops with a clear reason
 * instead of failing deep inside the Gradle log.
 */
object Preflight {
    fun compare(a: String, b: String): Int = Toolchain.compare(a, b)

    /** Android Gradle Plugin version declared by the project, from the version catalog or the root build file. */
    fun agpVersion(dir: File): String? = Toolchain.agpVersion(dir)

    /** Smallest Gradle the project's plugin accepts, or null when the plugin line is not in the table. */
    fun minGradle(dir: File): String? = Toolchain.minGradle(Toolchain.agpVersion(dir), LearnedStore.map(dir))

    /** Order: the user's choice, the project's wrapper (raised to what the plugin needs), the plugin's minimum, the default. */
    fun pickGradle(dir: File): GradlePick =
        Toolchain.pickGradle(ProjectMeta.gradleOverride(dir), Toolchain.wrapperVersion(dir), Toolchain.agpVersion(dir), LearnedStore.map(dir))

    fun installedPlatforms(env: BuildEnvironment): List<Int> =
        env.listPackages("platform").entries.filter { it.installed }.mapNotNull { it.id.toIntOrNull() }

    /** Highest API level installed on this device, or null when none is. */
    fun maxInstalledPlatform(env: BuildEnvironment): Int? = installedPlatforms(env).maxOrNull()

    /** Highest API level installed or listed in Google's repository (may use the network, cached for a day). */
    fun maxKnownPlatform(env: BuildEnvironment): Int? =
        env.listPackages("platform").entries.mapNotNull { it.id.toIntOrNull() }.maxOrNull()
}
