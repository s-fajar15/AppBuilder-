package dev.appbuilder

import java.io.File
import java.util.Properties

/** Pairs "plugin version -> minimum Gradle" learned from "Minimum supported Gradle version is X" errors. */
object LearnedToolchain {
    private val re = Regex("Minimum supported Gradle version is (\\d+(?:\\.\\d+){1,2})")
    private val plain = Regex("^\\d+(\\.\\d+){1,2}$")

    fun parse(log: String, agp: String?): Pair<String, String>? {
        if (agp == null || !plain.matches(agp)) return null
        val g = re.find(log)?.groupValues?.get(1) ?: return null
        return agp to g
    }

    fun toMap(text: String): Map<String, String> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            .filter { plain.matches(it.key) && plain.matches(it.value) }

    /** Keeps the higher Gradle when the plugin version is already stored. Sorted and idempotent. */
    fun merge(existingText: String, agp: String, gradle: String): String {
        val m = toMap(existingText).toMutableMap()
        val old = m[agp]
        if (old == null || Toolchain.compare(gradle, old) > 0) m[agp] = gradle
        return m.entries.sortedWith { a, b -> Toolchain.compare(a.key, b.key) }.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" }
    }
}

/** Per-device store of learned pairs, in the app's files dir. Set [file] once at startup. */
object LearnedStore {
    @Volatile var file: File? = null

    fun map(@Suppress("UNUSED_PARAMETER") dir: File? = null): Map<String, String> =
        try { file?.takeIf { it.exists() }?.readText()?.let(LearnedToolchain::toMap).orEmpty() } catch (_: Exception) { emptyMap() }

    fun learn(log: String, agp: String?): Boolean {
        val f = file ?: return false
        val pair = LearnedToolchain.parse(log, agp) ?: return false
        return try {
            val old = if (f.exists()) f.readText() else ""
            val merged = LearnedToolchain.merge(old, pair.first, pair.second)
            if (merged != old) { f.parentFile?.mkdirs(); f.writeText(merged) }
            merged != old
        } catch (_: Exception) { false }
    }
}

/** The small per-project settings file (.appbuilder). Pure java so it can be tested without Android. */
object ProjectMeta {
    private const val FILE = ".appbuilder"
    private val ver = Regex("^\\d+(\\.\\d+){1,2}(-[A-Za-z0-9]+)*$")

    private fun load(dir: File): Properties = Properties().also { p -> File(dir, FILE).takeIf { it.exists() }?.inputStream()?.use { p.load(it) } }

    private fun store(dir: File, p: Properties) { File(dir, FILE).outputStream().use { p.store(it, null) } }

    /** Set when the user accepts "Use Gradle X" after a version error. Takes priority over the wrapper file. */
    fun gradleOverride(dir: File): String? = load(dir).getProperty("gradleVersion")?.takeIf { ver.matches(it) }

    fun setGradleOverride(dir: File, version: String) { val p = load(dir); p["gradleVersion"] = version; store(dir, p) }

    fun clearGradleOverride(dir: File) { val p = load(dir); p.remove("gradleVersion"); store(dir, p) }

    fun heapOverrideMb(dir: File): Int? = load(dir).getProperty("heapMb")?.toIntOrNull()?.takeIf { it in 512..8192 }

    fun setHeapOverrideMb(dir: File, mb: Int) { val p = load(dir); p["heapMb"] = mb.toString(); store(dir, p) }

    fun kotlinInProcess(dir: File): Boolean = load(dir).getProperty("kotlinInProcess") == "true"

    fun setKotlinInProcess(dir: File, on: Boolean) { val p = load(dir); p["kotlinInProcess"] = on.toString(); store(dir, p) }

    fun offline(dir: File): Boolean = load(dir).getProperty("offline") == "true"

    fun setOffline(dir: File, on: Boolean) { val p = load(dir); p["offline"] = on.toString(); store(dir, p) }

    fun setLastBuild(dir: File, ok: Boolean) { val p = load(dir); p["lastBuild"] = if (ok) "ok" else "failed"; store(dir, p) }
}
