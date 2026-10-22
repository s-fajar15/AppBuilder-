package dev.appbuilder

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

const val META = ".appbuilder"
const val RUNNING = ".appbuilder-running"
const val HISTORY = ".appbuilder-history"

const val APK_MIME = "application/vnd.android.package-archive"

enum class BuildVariant(val label: String, val task: String, val path: String, val mime: String, val suffix: String, val button: String) {
    DEBUG("Debug", "assembleDebug", "app/build/outputs/apk/debug/app-debug.apk", APK_MIME, "debug.apk", "Build debug APK"),
    RELEASE_APK("Release APK", "assembleRelease", "app/build/outputs/apk/release/app-release.apk", APK_MIME, "release.apk", "Build release APK"),
    RELEASE_AAB("Release AAB", "bundleRelease", "app/build/outputs/bundle/release/app-release.aab", "application/octet-stream", "release.aab", "Build release AAB");

    val release: Boolean get() = this != DEBUG

    companion object {
        fun fromLabel(l: String): BuildVariant = values().firstOrNull { it.label == l } ?: DEBUG
    }
}

data class Project(
    val dir: File,
    val name: String,
    val pkg: String,
    val language: String,
    val ui: String,
    val modified: Long,
    val buildFailed: Boolean,
) {
    val apk: File get() = File(dir, "app/build/outputs/apk/debug/app-debug.apk")
    val log: File get() = File(dir, "$META-build.log")
    fun output(v: BuildVariant): File = File(dir, v.path)
    val displayPath: String get() = "/Projects/${dir.name}"
}

sealed interface Analysis {
    val fileName: String
    val size: Long
}

data class Valid(
    override val fileName: String, override val size: Long, val cache: File, val prefix: String,
    val language: String, val ui: String, val compileSdk: String, val pkg: String, val suggestedName: String,
) : Analysis

data class Invalid(override val fileName: String, override val size: Long, val missing: List<String>) : Analysis

class ProjectRepo(context: Context) {
    private val cache = context.cacheDir
    private val resolver = context.contentResolver
    val root = File(context.filesDir, "Projects").apply { mkdirs() }

    fun list(): List<Project> =
        root.listFiles { f -> f.isDirectory }.orEmpty().map(::load).sortedByDescending { it.modified }

    fun load(dir: File): Project {
        val meta = Properties()
        val mf = File(dir, META)
        if (mf.exists()) mf.inputStream().use { meta.load(it) }
        return Project(
            dir = dir,
            name = meta.getProperty("name") ?: dir.name,
            pkg = meta.getProperty("package") ?: "",
            language = meta.getProperty("language") ?: "Kotlin",
            ui = meta.getProperty("ui") ?: "XML",
            modified = if (mf.exists()) mf.lastModified() else dir.lastModified(),
            buildFailed = meta.getProperty("lastBuild") == "failed",
        )
    }

    private fun writeMeta(dir: File, name: String, pkg: String, lang: String, ui: String) {
        val p = Properties()
        p["name"] = name; p["package"] = pkg; p["language"] = lang; p["ui"] = ui
        File(dir, META).outputStream().use { p.store(it, null) }
    }

    fun touch(p: Project) { File(p.dir, META).setLastModified(System.currentTimeMillis()) }

    fun delete(p: Project) { p.dir.deleteRecursively() }

    // ---- validation ----
    fun validateName(name: String): String? = when {
        !Regex("^[A-Za-z][A-Za-z0-9 _-]{0,39}$").matches(name) -> "Use letters, numbers, space, - or _"
        File(root, name.trim()).exists() -> "A project with this name already exists"
        else -> null
    }

    fun validatePackage(pkg: String): String? =
        if (!Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$").matches(pkg)) "Example: com.example.myapp" else null

    // ---- create ----
    fun create(name: String, pkg: String, lang: String, ui: String): Project {
        val dir = File(root, name.trim())
        for ((path, text) in Templates.files(name.trim(), pkg, lang, ui)) {
            val f = File(dir, path)
            f.parentFile?.mkdirs()
            f.writeText(text)
        }
        writeMeta(dir, name.trim(), pkg, lang, ui)
        return load(dir)
    }

    // ---- import ----
    fun analyze(uri: Uri): Analysis {
        var fileName = "project.zip"
        var size = 0L
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { fileName = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
            }
        }
        val tmp = File(cache, "import.zip")
        resolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        if (size == 0L) size = tmp.length()
        return try {
            ZipFile(tmp).use { zip -> inspect(zip, tmp, fileName, size) }
        } catch (e: Exception) {
            Invalid(fileName, size, listOf("settings.gradle.kts", "app/build.gradle.kts"))
        }
    }

    private fun inspect(zip: ZipFile, tmp: File, fileName: String, size: Long): Analysis {
        val names = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
        val settings = names
            .filter { it.substringAfterLast('/') in setOf("settings.gradle", "settings.gradle.kts") }
            .minByOrNull { it.count { c -> c == '/' } }
        val prefix = settings?.substringBeforeLast('/', "")?.let { if (it.isEmpty()) "" else "$it/" } ?: ""
        val appBuild = listOf("app/build.gradle.kts", "app/build.gradle").map { prefix + it }.firstOrNull { it in names }
        val missing = buildList {
            if (settings == null) add("settings.gradle.kts")
            if (appBuild == null) add("app/build.gradle.kts")
        }
        if (missing.isNotEmpty()) return Invalid(fileName, size, missing)
        val text = zip.getInputStream(zip.getEntry(appBuild!!)).bufferedReader().use { it.readText() }
        val lang = if (names.any { it.endsWith(".kt") }) "Kotlin" else "Java"
        val ui = if (text.contains("compose", ignoreCase = true)) "Jetpack Compose" else "XML"
        val sdk = Regex("compileSdk[^\\d\\n]*(\\d+)").find(text)?.groupValues?.get(1) ?: "unknown"
        val pkg = Regex("namespace\\s*=\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            ?: Regex("applicationId\\s*=?\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: ""
        return Valid(fileName, size, tmp, prefix, lang, ui, sdk, pkg, fileName.removeSuffix(".zip").replace(Regex("[^A-Za-z0-9 _-]"), ""))
    }

    fun importZip(a: Valid, name: String): Project {
        val dir = File(root, name.trim())
        val base = dir.canonicalPath + File.separator
        ZipFile(a.cache).use { zip ->
            for (e in zip.entries()) {
                if (!e.name.startsWith(a.prefix) || e.name == a.prefix) continue
                val out = File(dir, e.name.removePrefix(a.prefix))
                if (!out.canonicalPath.startsWith(base)) continue // zip-slip guard
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    zip.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                }
            }
        }
        writeMeta(dir, name.trim(), a.pkg, a.language, if (a.ui == "XML") "XML" else "Compose")
        a.cache.delete()
        return load(dir)
    }

    /** Registers a folder that was cloned (not imported from a ZIP) as a project, reading what it can from the build files. */
    fun adopt(dir: File): Project {
        val build = listOf("app/build.gradle.kts", "app/build.gradle").map { File(dir, it) }.firstOrNull { it.exists() }
        val text = build?.readText().orEmpty()
        val pkg = Regex("namespace\\s*=?\\s*[\"']([^\"']+)[\"']").find(text)?.groupValues?.get(1) ?: ""
        val kotlin = dir.walkTopDown().onEnter { it.name != ".git" && it.name != "build" }.take(4000).any { it.name.endsWith(".kt") }
        val compose = text.contains("compose", ignoreCase = true)
        writeMeta(dir, dir.name, pkg, if (kotlin) "Kotlin" else "Java", if (compose) "Compose" else "XML")
        return load(dir)
    }

    // ---- export ----
    fun export(p: Project): File {
        val out = File(File(cache, "exports").apply { mkdirs() }, "${p.dir.name}.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            p.dir.walkTopDown()
                .onEnter { it.name != "build" && it.name != ".gradle" && !it.name.startsWith(META) }
                .filter { it.isFile && !it.name.startsWith(META) }
                .forEach { f ->
                    zos.putNextEntry(ZipEntry("${p.dir.name}/${f.relativeTo(p.dir).path}"))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
        }
        return out
    }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var theme: String get() = sp.getString("theme", "System")!!; set(v) = sp.edit().putString("theme", v).apply()
    var fontSize: Int get() = sp.getInt("fontSize", 13); set(v) = sp.edit().putInt("fontSize", v.coerceIn(9, 28)).apply()
    var wordWrap: Boolean get() = sp.getBoolean("wordWrap", true); set(v) = sp.edit().putBoolean("wordWrap", v).apply()
    var language: String get() = sp.getString("language", "System")!!; set(v) = sp.edit().putString("language", v).apply()
    var catalogUrl: String get() = sp.getString("catalogUrl", "")!!; set(v) = sp.edit().putString("catalogUrl", v).apply()
    var keyFile: String get() = sp.getString("keyFile", "")!!; set(v) = sp.edit().putString("keyFile", v).apply()
    var keyAlias: String get() = sp.getString("keyAlias", "")!!; set(v) = sp.edit().putString("keyAlias", v).apply()
    var keyPass: String get() = sp.getString("keyPass", "")!!; set(v) = sp.edit().putString("keyPass", v).apply()

    fun signing(): Signing? = keyFile.takeIf { it.isNotEmpty() && File(it).exists() && keyAlias.isNotEmpty() }?.let { Signing(File(it), keyAlias, keyPass) }
}

fun timeAgo(ms: Long): String {
    val d = System.currentTimeMillis() - ms
    val m = d / 60_000
    return when {
        m < 1 -> "Just now"
        m < 60 -> "$m min ago"
        m < 60 * 24 -> "${m / 60} h ago"
        m < 60 * 48 -> "Yesterday"
        m < 60 * 24 * 7 -> "${m / (60 * 24)} days ago"
        else -> "Last week"
    }
}

fun formatSize(b: Long): String = when {
    b >= 1_000_000 -> "%.1f MB".format(b / 1_000_000.0)
    b >= 1_000 -> "%.0f KB".format(b / 1_000.0)
    else -> "$b B"
}
