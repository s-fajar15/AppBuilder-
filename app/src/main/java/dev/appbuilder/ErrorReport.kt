package dev.appbuilder

data class ReportInput(
    val appVersion: String, val androidVersion: String, val sdkInt: Int, val abi: String,
    val availRamMb: Long, val totalRamMb: Long,
    val gradle: String?, val gradleSource: String?, val jdk: String?, val agp: String?, val kotlin: String?,
    val compileSdk: String?, val targetSdk: String?, val minSdk: String?,
    val installedPlatforms: List<Int>, val installedBuildTools: List<String>,
    val variant: String, val whatWentWrong: String, val diagnosisTitle: String?, val log: String,
    /** Folders whose names must not leave the phone (app files, project folder, home). */
    val privatePaths: List<String> = emptyList(),
)

object ErrorReport {
    private val secretKey = "(?:storePassword|keyPassword|store_password|key_password|password|passwd|secret|token|apikey|api_key|AB_STORE_PASS|AB_KEY_PASS|AB_STORE|AB_KEY_ALIAS)"
    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("(?i)(Authorization\\s*[:=]\\s*)(Bearer|token|Basic)\\s+\\S+") to "$1$2 ***",
        Regex("(?i)(https?://)[^/\\s:@]+:[^/\\s@]+@") to "$1***@",
        Regex("github_pat_[A-Za-z0-9_]{20,}") to "***",
        Regex("gh[pousr]_[A-Za-z0-9]{20,}") to "***",
        Regex("(?i)(-P\\w*(?:pass|secret|token|key)\\w*=)\\S+") to "$1***",
        Regex("(?i)\\b($secretKey)(\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|\\S+)") to "$1$2***",
        Regex("[A-Za-z0-9+/=]{80,}") to "<redacted>",
    )

    fun sanitize(text: String, privatePaths: List<String>): String {
        var s = text
        for (p in privatePaths.filter { it.length > 3 }.distinct().sortedByDescending { it.length }) s = s.replace(p.trimEnd('/'), "<app>")
        s = s.replace(Regex("/data/(?:user/\\d+|data)/[\\w.]+"), "<app>")
        s = s.replace(Regex("/storage/emulated/\\d+"), "<storage>")
        s = s.replace(Regex("/(?:home|Users)/[^/\\s]+"), "~")
        for ((re, to) in rules) s = re.replace(s, to)
        return s
    }

    fun build(i: ReportInput): String {
        val sb = StringBuilder()
        sb.append("AppBuilder error report\n\n")
        sb.append("Environment\n")
        sb.append("- AppBuilder: ${i.appVersion}\n")
        sb.append("- Android: ${i.androidVersion} (API ${i.sdkInt}), ${i.abi}\n")
        sb.append("- RAM: ${i.availRamMb} MB free of ${i.totalRamMb} MB\n\n")
        sb.append("Toolchain\n")
        sb.append("- Gradle: ${i.gradle ?: "default"}${i.gradleSource?.let { " ($it)" } ?: ""}\n")
        sb.append("- JDK: ${i.jdk ?: "unknown"}\n")
        sb.append("- Android Gradle Plugin: ${i.agp ?: "unknown"}\n")
        sb.append("- Kotlin: ${i.kotlin ?: "unknown"}\n")
        sb.append("- compileSdk/targetSdk/minSdk: ${i.compileSdk ?: "?"}/${i.targetSdk ?: "?"}/${i.minSdk ?: "?"}\n")
        sb.append("- Platforms installed: ${i.installedPlatforms.sorted().joinToString().ifEmpty { "none" }}\n")
        sb.append("- Build tools installed: ${i.installedBuildTools.joinToString().ifEmpty { "none" }}\n")
        sb.append("- Build: ${i.variant}\n\n")
        i.diagnosisTitle?.let { sb.append("Diagnosis: $it\n\n") }
        sb.append("What went wrong\n").append(sanitize(i.whatWentWrong.ifBlank { "(not available)" }, i.privatePaths)).append("\n\n")
        sb.append("Last 80 log lines\n```\n")
        sb.append(sanitize(i.log.lines().takeLast(80).joinToString("\n"), i.privatePaths)).append("\n```\n")
        return sb.toString()
    }
}
