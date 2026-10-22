package dev.appbuilder

import java.io.File

/** Reads and edits the real Gradle files of a project. Only keys that exist are exposed. */
object GradleEdit {
    val keys = listOf("appName", "applicationId", "versionName", "versionCode", "minSdk", "targetSdk", "compileSdk", "kotlin", "gradle")
    val labels = mapOf(
        "appName" to "App name", "applicationId" to "Application ID", "versionName" to "Version name",
        "versionCode" to "Version code", "minSdk" to "Minimum SDK", "targetSdk" to "Target SDK",
        "compileSdk" to "Compile SDK", "kotlin" to "Kotlin version", "gradle" to "Gradle version",
    )

    fun appBuild(dir: File): File? = listOf("app/build.gradle.kts", "app/build.gradle").map { File(dir, it) }.firstOrNull { it.exists() }
    private fun rootBuild(dir: File): File? = listOf("build.gradle.kts", "build.gradle").map { File(dir, it) }.firstOrNull { it.exists() }
    private fun wrapper(dir: File) = File(dir, "gradle/wrapper/gradle-wrapper.properties")
    private fun strings(dir: File) = File(dir, "app/src/main/res/values/strings.xml")

    private fun intRe(key: String) = Regex("(\\b$key[^\\d\\n\"]*)(\\d+)")
    private fun strRe(key: String) = Regex("(\\b$key\\s*=?\\s*[\"'])([^\"']*)([\"'])")
    private val kotlinRe = Regex("(kotlin[\"')(.a-z]*\\s*version\\s*[\"'])([^\"']+)([\"'])")
    private val gradleRe = Regex("(gradle-)([\\d.]+(?:-[a-z0-9]+)?)(-(?:bin|all)\\.zip)")
    private val nameRe = Regex("(<string name=\"app_name\">)([^<]*)(</string>)")

    private fun locate(key: String, dir: File): Pair<File, Regex>? = when (key) {
        "appName" -> strings(dir).takeIf { it.exists() }?.let { it to nameRe }
        "applicationId" -> appBuild(dir)?.let { it to strRe("applicationId") }
        "versionName" -> appBuild(dir)?.let { it to strRe("versionName") }
        "versionCode", "minSdk", "targetSdk", "compileSdk" -> appBuild(dir)?.let { it to intRe(key) }
        "kotlin" -> rootBuild(dir)?.let { it to kotlinRe }
        "gradle" -> wrapper(dir).takeIf { it.exists() }?.let { it to gradleRe }
        else -> null
    }

    /** Gradle version named in gradle-wrapper.properties, or null. */
    fun wrapperVersion(dir: File): String? = Toolchain.wrapperVersion(dir)

    fun read(dir: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (k in keys) {
            val (f, re) = locate(k, dir) ?: continue
            re.find(f.readText())?.let { out[k] = it.groupValues[2] }
        }
        return out
    }

    fun validate(v: Map<String, String>, repo: ProjectRepo): Map<String, String> {
        val e = HashMap<String, String>()
        v["applicationId"]?.let { repo.validatePackage(it)?.let { m -> e["applicationId"] = m } }
        v["appName"]?.let { if (it.isBlank() || it.contains('<')) e["appName"] = "Enter a valid name" }
        val ver = Regex("^\\d+(\\.\\d+)*([-.+\\w]*)$")
        v["versionName"]?.let { if (!ver.matches(it)) e["versionName"] = "Example: 1.0.0" }
        v["kotlin"]?.let { if (!ver.matches(it)) e["kotlin"] = "Example: 2.1.0" }
        v["gradle"]?.let { if (!ver.matches(it)) e["gradle"] = "Example: 8.11.1" }
        v["versionCode"]?.let { if (it.toIntOrNull()?.let { n -> n >= 1 } != true) e["versionCode"] = "Must be 1 or higher" }
        val min = v["minSdk"]?.toIntOrNull(); val tgt = v["targetSdk"]?.toIntOrNull(); val cmp = v["compileSdk"]?.toIntOrNull()
        if (v.containsKey("minSdk") && (min == null || min < 21)) e["minSdk"] = "Use a number from 21"
        if (v.containsKey("targetSdk") && (tgt == null || (min != null && tgt < min))) e["targetSdk"] = "Must be at least Minimum SDK"
        if (v.containsKey("compileSdk") && (cmp == null || (tgt != null && cmp < tgt))) e["compileSdk"] = "Must be at least Target SDK"
        return e
    }

    fun write(dir: File, values: Map<String, String>) {
        val current = read(dir)
        val texts = HashMap<File, String>()
        for ((k, v) in values) {
            if (current[k] == v) continue
            val (f, re) = locate(k, dir) ?: continue
            val text = texts[f] ?: f.readText()
            val m = re.find(text) ?: continue
            val r = m.groups[2]!!.range
            texts[f] = text.replaceRange(r, v)
        }
        texts.forEach { (f, t) -> saveAtomic(f, t) }
    }
}

data class Dep(val config: String, val group: String, val artifact: String, val version: String, val line: Int)

object Deps {
    private val configs = setOf("implementation", "api", "ksp", "kapt", "compileOnly", "runtimeOnly", "debugImplementation", "testImplementation", "androidTestImplementation")
    private val re = Regex("""^\s*(\w+)\s*\(?\s*["']([^"':]+):([^"':]+)(?::([^"']*))?["']""")

    fun list(dir: File): List<Dep> {
        val f = GradleEdit.appBuild(dir) ?: return emptyList()
        return f.readLines().mapIndexedNotNull { i, l ->
            val m = re.find(l) ?: return@mapIndexedNotNull null
            if (m.groupValues[1] !in configs) null else Dep(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4], i)
        }
    }

    private fun render(f: File, indent: String, config: String, g: String, a: String, v: String): String {
        val coord = if (v.isBlank()) "$g:$a" else "$g:$a:$v"
        return if (f.name.endsWith(".kts")) "$indent$config(\"$coord\")" else "$indent$config \"$coord\""
    }

    fun validate(g: String, a: String, v: String): String? {
        val ok = Regex("^[A-Za-z0-9_.\\-]+$")
        return when {
            !ok.matches(g) -> "Group is required, e.g. androidx.core"
            !ok.matches(a) -> "Artifact is required, e.g. core-ktx"
            v.isNotEmpty() && !Regex("^[A-Za-z0-9_.+\\-]+$").matches(v) -> "Invalid version"
            else -> null
        }
    }

    fun add(dir: File, g: String, a: String, v: String) {
        val f = GradleEdit.appBuild(dir) ?: throw IllegalStateException("app/build.gradle(.kts) not found")
        val lines = f.readLines().toMutableList()
        val start = lines.indexOfLast { it.matches(Regex("^dependencies\\s*\\{.*")) }
        val newLine = render(f, "    ", "implementation", g, a, v)
        if (start < 0) { lines += listOf("", "dependencies {", newLine, "}") }
        else {
            val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("}") } ?: lines.size
            lines.add(end, newLine)
        }
        saveAtomic(f, lines.joinToString("\n") + "\n")
    }

    fun remove(dir: File, d: Dep) {
        val f = GradleEdit.appBuild(dir) ?: return
        val lines = f.readLines().toMutableList()
        if (d.line in lines.indices) lines.removeAt(d.line)
        saveAtomic(f, lines.joinToString("\n") + "\n")
    }

    fun update(dir: File, d: Dep, version: String) {
        val f = GradleEdit.appBuild(dir) ?: return
        val lines = f.readLines().toMutableList()
        if (d.line !in lines.indices) return
        val indent = lines[d.line].takeWhile { it == ' ' || it == '\t' }
        lines[d.line] = render(f, indent, d.config, d.group, d.artifact, version)
        saveAtomic(f, lines.joinToString("\n") + "\n")
    }
}
