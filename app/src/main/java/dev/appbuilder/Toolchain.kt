package dev.appbuilder

import java.io.File

data class GradlePick(val version: String?, val reason: String)

data class ToolchainPreset(val id: String, val title: String, val agp: String, val gradle: String, val jdk: Int, val compileSdk: Int, val note: String)

/** What each Android Gradle Plugin line needs. Pure logic, no Android types, so it is unit-tested on the JVM. */
object Toolchain {
    // The pair the project templates use and that has been built on a device.
    const val TESTED_AGP = "8.9.1"
    const val TESTED_GRADLE = "8.11.1"
    const val TESTED_KOTLIN = "2.1.0"
    const val TESTED_API = 36

    /** Oldest Gradle each Android Gradle Plugin line needs (major.minor of the plugin to Gradle). */
    private val agpToGradle = listOf(
        "7.4" to "7.5", "8.0" to "8.0", "8.1" to "8.0", "8.2" to "8.2", "8.3" to "8.4", "8.4" to "8.6",
        "8.5" to "8.7", "8.6" to "8.7", "8.7" to "8.9", "8.8" to "8.10.2", "8.9" to "8.11.1",
        "8.10" to "8.11.1", "8.11" to "8.13", "8.12" to "8.13", "8.13" to "8.13",
        "9.0" to "9.1.0", "9.3" to "9.5.0",
    )

    /** Minimum JDK to run each plugin generation (from the AGP release notes). */
    private fun jdkFor(major: Int): Int? = when {
        major <= 6 -> 8
        major == 7 -> 11
        major >= 8 -> 17
        else -> null
    }

    fun key(v: String): List<Int> = v.split('.', '-').mapNotNull { it.toIntOrNull() }

    fun compare(a: String, b: String): Int {
        val x = key(a); val y = key(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun tomlVersion(toml: String, name: String): String? {
        val want = norm(name)
        for (m in Regex("""(?m)^\s*([\w.-]+)\s*=\s*"([^"]+)"""").findAll(toml)) if (norm(m.groupValues[1]) == want) return m.groupValues[2]
        return null
    }

    /** Android Gradle Plugin version declared by the project, from the version catalog or the root build file. */
    fun agpVersion(dir: File): String? {
        val catalog = File(dir, "gradle/libs.versions.toml")
        val toml = if (catalog.exists()) catalog.readText() else null
        if (toml != null) {
            // [plugins] android-application = { id = "com.android.application", version.ref = "x" }  or version = "8.x"
            for (line in toml.lines()) {
                if (!line.contains("com.android.application") && !line.contains("com.android.library")) continue
                Regex("""version\.ref\s*=\s*"([^"]+)"""").find(line)?.let { r -> tomlVersion(toml, r.groupValues[1])?.let { return it } }
                Regex("""version\s*=\s*"([^"]+)"""").find(line)?.let { return it.groupValues[1] }
            }
            Regex("""(?m)^\s*(?:agp|androidGradlePlugin|android-gradle-plugin)\s*=\s*"([^"]+)"""").find(toml)?.groupValues?.get(1)?.let { return it }
        }
        val root = listOf("build.gradle.kts", "build.gradle").map { File(dir, it) }.firstOrNull { it.exists() } ?: return null
        val text = root.readText()
        Regex("""com\.android\.(?:application|library)["')\s]*version\s*["']([^"']+)["']""").find(text)?.groupValues?.get(1)?.let { return it }
        Regex("""com\.android\.tools\.build:gradle:([\d.]+[\w.-]*)""").find(text)?.groupValues?.get(1)?.let { return it }
        if (toml != null) Regex("""version\s+libs\.versions\.([\w.]+)\.get\(\)""").find(text)?.let { m -> tomlVersion(toml, m.groupValues[1].replace('.', '-'))?.let { return it } }
        return null
    }

    private val wrapperRe = Regex("(gradle-)([\\d.]+(?:-[a-z0-9]+)*?)(-(?:bin|all)\\.zip)")
    private val versionOk = Regex("^\\d+(\\.\\d+){1,2}(-[A-Za-z0-9]+)*$")

    /** Gradle version named in gradle-wrapper.properties, or null. Validated because it ends up in a URL and a path. */
    fun wrapperVersion(dir: File): String? {
        val f = File(dir, "gradle/wrapper/gradle-wrapper.properties").takeIf { it.exists() } ?: return null
        val v = wrapperRe.find(f.readText())?.groupValues?.get(2) ?: return null
        return v.takeIf { versionOk.matches(it) }
    }

    /** Imported projects often lack gradle-wrapper.properties (it is usually git-ignored or stripped). Writes one with the version [pickGradle] chooses. Returns the version, or null when nothing was written. */
    fun ensureWrapperProps(dir: File, learned: Map<String, String> = emptyMap()): String? {
        val f = File(dir, "gradle/wrapper/gradle-wrapper.properties")
        if (f.exists()) return null
        val v = pickGradle(null, null, agpVersion(dir), learned).version ?: return null
        f.parentFile?.mkdirs()
        f.writeText(
            "distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\n" +
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-$v-bin.zip\n" +
                "zipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n",
        )
        return v
    }

    fun kotlinVersion(dir: File): String? {
        val catalog = File(dir, "gradle/libs.versions.toml")
        if (catalog.exists()) Regex("""(?m)^\s*kotlin\s*=\s*"([^"]+)"""").find(catalog.readText())?.let { return it.groupValues[1] }
        val root = listOf("build.gradle.kts", "build.gradle").map { File(dir, it) }.firstOrNull { it.exists() } ?: return null
        return Regex("""kotlin["')(.a-z]*\s*version\s*["']([^"']+)["']""").find(root.readText())?.groupValues?.get(1)
    }

    private fun lowerBound(agp: String, learned: Map<String, String>): String? {
        learned[agp]?.let { return it }
        val k = key(agp)
        if (k.size < 2) return null
        val known = agpToGradle.map { (a, g) -> key(a) to g } +
            learned.mapNotNull { (a, g) -> key(a).takeIf { it.size >= 2 }?.let { it to g } }
        val exact = known.filter { it.first[0] == k[0] && it.first[1] == k[1] }.maxByOrNull { key(it.second).fold(0L) { acc, n -> acc * 1000 + n } }
        if (exact != null) return exact.second
        // Same major, newer minor than we know: the newest older entry is a safe lower bound.
        return known.filter { it.first[0] == k[0] && it.first[1] <= k[1] }.maxByOrNull { it.first[1] }?.second
    }

    /** Smallest Gradle the plugin accepts, or null when the plugin line is not in the table. [learned] holds pairs read from past errors. */
    fun minGradle(agp: String?, learned: Map<String, String> = emptyMap()): String? = agp?.let { lowerBound(it, learned) }

    fun minGradleFor(dir: File, learned: Map<String, String> = emptyMap()): String? = minGradle(agpVersion(dir), learned)

    fun minJdk(agp: String?): Int? = agp?.let { key(it).firstOrNull() }?.let(::jdkFor)

    /** Order: the user's choice, the project's wrapper (raised to what the plugin needs), the plugin's minimum, the default. */
    fun pickGradle(override: String?, wrapper: String?, agp: String?, learned: Map<String, String> = emptyMap()): GradlePick {
        if (override != null) return GradlePick(override, "your choice")
        val min = minGradle(agp, learned)
        return when {
            wrapper != null && (min == null || compare(wrapper, min) >= 0) -> GradlePick(wrapper, "gradle-wrapper.properties")
            min != null && wrapper != null -> GradlePick(min, "raised from wrapper $wrapper: Android Gradle Plugin $agp needs $min or newer")
            min != null -> GradlePick(min, "required by Android Gradle Plugin $agp")
            else -> GradlePick(null, "default")
        }
    }

    /** When the user's own Gradle choice is older than the plugin needs, the version that would be needed; otherwise null. */
    fun overrideTooOld(override: String?, agp: String?, learned: Map<String, String> = emptyMap()): String? {
        val min = minGradle(agp, learned) ?: return null
        return if (override != null && compare(override, min) < 0) min else null
    }

    /** "21.0.12", "17.0.9", "1.8.0_392", `openjdk version "21.0.12.1" 2026-08-18` -> major version. */
    fun jdkMajor(versionOutput: String): Int? {
        val quoted = Regex("\"([^\"]+)\"").find(versionOutput)?.groupValues?.get(1)
        val s = quoted ?: Regex("\\d+(?:\\.\\d+)*(?:_\\d+)?").find(versionOutput.trim())?.value ?: return null
        val parts = s.split('.', '_', '-', '+').mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return null
        return if (parts[0] == 1 && parts.size > 1) parts[1] else parts[0]
    }

    fun presets(): List<ToolchainPreset> = listOf(
        ToolchainPreset("agp9", "AGP 9.x", "9.3.3", "9.5.0", 21, 36, "Newest plugin line. Needs Gradle 9.5 or newer."),
        ToolchainPreset("agp813", "AGP 8.13", "8.13.0", "8.13", 17, 36, "Latest 8.x line."),
        ToolchainPreset("agp89", "AGP 8.9 (default template)", TESTED_AGP, TESTED_GRADLE, 17, TESTED_API, "The combination new projects use."),
    )
}
