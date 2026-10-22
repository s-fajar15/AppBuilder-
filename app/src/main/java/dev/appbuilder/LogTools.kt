package dev.appbuilder

data class LogHit(val line: Int, val text: String)

enum class Phase { DOWNLOAD, CONFIGURE, COMPILE, PACKAGE, OTHER }

data class PhaseTime(val phase: Phase, val ms: Long)

object LogTools {
    /** Gradle prints the real cause under "* What went wrong:". Show that, not the last line of the log. */
    fun whatWentWrong(log: String): String {
        val lines = log.lines()
        val i = lines.indexOfFirst { it.startsWith("* What went wrong:") }
        val head = if (i < 0) lines.lastOrNull { it.isNotBlank() }.orEmpty()
        else lines.drop(i + 1).takeWhile { !it.startsWith("* Try:") }.joinToString("\n").trim()
        val cause = lines.lastOrNull { it.trimStart().startsWith("Caused by:") }?.trim()
        return if (cause != null && !head.contains(cause)) head + "\n\nRoot cause: " + cause else head
    }

    private val errorLine = Regex("^(e: |w: ).*|.*\\berror:.*|^ERROR:.*|.*\\bFAILED\\b.*|^FAILURE:.*|^BUILD FAILED.*|\\s*Caused by:.*|.*Exception.*|.*aapt2?\\b.*error.*", RegexOption.IGNORE_CASE)

    /** Only the lines that explain a failure, with one line of context. Warnings ("w: ") are left out. */
    fun errorsOnly(log: String): String {
        if (log.isBlank()) return ""
        val lines = log.lines()
        val keep = BooleanArray(lines.size)
        var inWrong = false
        for ((i, l) in lines.withIndex()) {
            if (l.startsWith("* What went wrong:")) inWrong = true
            else if (l.startsWith("* Try:") || l.startsWith("* Get more help")) inWrong = false
            val hit = inWrong || (errorLine.matches(l) && !l.startsWith("w: "))
            if (hit) { keep[i] = true; if (i > 0) keep[i - 1] = true; if (i + 1 < lines.size) keep[i + 1] = true }
        }
        val out = StringBuilder()
        var gap = false
        for ((i, l) in lines.withIndex()) {
            if (keep[i]) { if (gap && out.isNotEmpty()) out.append("...\n"); out.append(l).append('\n'); gap = false } else gap = true
        }
        return out.toString().trimEnd()
    }

    fun search(log: String, query: String, caseSensitive: Boolean = false, limit: Int = 500): List<LogHit> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<LogHit>()
        for ((i, l) in log.lineSequence().withIndex()) {
            if (l.contains(query, ignoreCase = !caseSensitive)) { out += LogHit(i + 1, l); if (out.size >= limit) break }
        }
        return out
    }

    /** "BUILD SUCCESSFUL in 1m 3s" and similar -> milliseconds. */
    fun totalDuration(log: String): Long? {
        val m = Regex("BUILD (?:SUCCESSFUL|FAILED) in ((?:\\d+h\\s*)?(?:\\d+m\\s*)?(?:\\d+s)?(?:\\s*\\d+ms)?)").findAll(log).lastOrNull() ?: return null
        val t = m.groupValues[1]
        if (t.isBlank()) return null
        var ms = 0L
        Regex("(\\d+)(ms|h|m|s)").findAll(t).forEach { x ->
            val n = x.groupValues[1].toLong()
            ms += when (x.groupValues[2]) { "h" -> n * 3_600_000; "m" -> n * 60_000; "s" -> n * 1000; else -> n }
        }
        return ms
    }

    fun format(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}

/** Gradle's plain console has no timestamps, so the app stamps each line as it arrives and attributes the wait to the phase of the line before. */
class PhaseTracker(private val clock: () -> Long = System::currentTimeMillis) {
    private val totals = LinkedHashMap<Phase, Long>()
    private var phase = Phase.OTHER
    private var last = -1L

    private fun classify(line: String): Phase? {
        val l = line.trim()
        return when {
            l.startsWith("Download") || l.startsWith("Starting a Gradle Daemon") || l.contains("Resolving dependencies") -> Phase.DOWNLOAD
            l.startsWith("> Configure project") || l.startsWith("Evaluating ") || l.startsWith("> Configure") || l.contains("Configuration on demand") -> Phase.CONFIGURE
            Regex("^> Task :\\S*(compile|kapt|ksp|kotlin|javac|dex|merge\\w*Resources|processResources|aapt|generate\\w+|process\\w*Manifest|mergeDex|desugar)\\S*", RegexOption.IGNORE_CASE).containsMatchIn(l) -> Phase.COMPILE
            Regex("^> Task :\\S*(package|assemble|bundle|sign|zipalign|lint|validateSigning|createApk)\\S*", RegexOption.IGNORE_CASE).containsMatchIn(l) -> Phase.PACKAGE
            else -> null
        }
    }

    fun onLine(line: String) {
        val now = clock()
        if (last >= 0) totals[phase] = (totals[phase] ?: 0L) + (now - last).coerceAtLeast(0)
        classify(line)?.let { phase = it }
        last = now
    }

    fun finish(): List<PhaseTime> {
        val now = clock()
        if (last >= 0) { totals[phase] = (totals[phase] ?: 0L) + (now - last).coerceAtLeast(0); last = now }
        return totals.filter { it.value >= 1000 || it.key != Phase.OTHER && it.value > 0 }.map { PhaseTime(it.key, it.value) }
    }
}
