package dev.appbuilder

sealed interface FixOp {
    data class UseGradle(val version: String) : FixOp
    data class SetCompileSdk(val api: Int) : FixOp
    data object OpenLicense : FixOp
    data object RetryBuild : FixOp
    data object RetryOffline : FixOp
    data object DisableAarCheck : FixOp
    data object UseOtherJdk : FixOp
    data class SetHeap(val mb: Int) : FixOp
    data object DisableKotlinDaemon : FixOp
    data object OpenSdk : FixOp
    data object OpenConfig : FixOp
    data object OpenDeps : FixOp
}

class FixAction(val label: String, val op: FixOp)

/** The one card above the log: the cause in plain words, one sentence of solution, at most two buttons. */
class Diagnosis(val id: String, val title: String, val solution: String, val actions: List<FixAction>, val details: String? = null)

/** What a fix may do. The screens implement this; tests use a fake. */
interface FixHost {
    fun useGradle(version: String)
    fun setCompileSdk(api: Int)
    fun openLicense()
    fun retryBuild(extraArgs: List<String> = emptyList())
    fun disableAarCheck()
    fun chooseJdk()
    fun setHeapMb(mb: Int)
    fun disableKotlinDaemon()
    fun openSdk()
    fun openConfig()
    fun openDeps()
}

fun FixAction.apply(host: FixHost) {
    when (val o = op) {
        is FixOp.UseGradle -> host.useGradle(o.version)
        is FixOp.SetCompileSdk -> host.setCompileSdk(o.api)
        FixOp.OpenLicense -> host.openLicense()
        FixOp.RetryBuild -> host.retryBuild()
        FixOp.RetryOffline -> host.retryBuild(listOf("--offline"))
        FixOp.DisableAarCheck -> host.disableAarCheck()
        FixOp.UseOtherJdk -> host.chooseJdk()
        is FixOp.SetHeap -> host.setHeapMb(o.mb)
        FixOp.DisableKotlinDaemon -> host.disableKotlinDaemon()
        FixOp.OpenSdk -> host.openSdk()
        FixOp.OpenConfig -> host.openConfig()
        FixOp.OpenDeps -> host.openDeps()
    }
}

data class FixContext(
    val projectCompileSdk: Int? = null, val maxInstalledPlatform: Int? = null, val maxKnownPlatform: Int? = null,
    val hasAltJdk: Boolean = false, val agp: String? = null, val kotlin: String? = null, val buildFile: String? = null,
    val heapMb: Int = 1536, val exitCode: Int? = null, val availRamMb: Long = 0, val totalRamMb: Long = 0,
)

sealed interface QuickFix {
    val id: String
    fun detect(log: String, ctx: FixContext): Diagnosis?
}

private fun actions(vararg a: FixAction?) = a.filterNotNull().take(2)

object GradleTooOldFix : QuickFix {
    override val id = "gradle-too-old"
    private val re = Regex("Minimum supported Gradle version is (\\d+(?:\\.\\d+)+)\\.?(?:\\s*Current version is (\\d+(?:\\.\\d+)+))?")
    override fun detect(log: String, ctx: FixContext): Diagnosis? {
        val m = re.find(log) ?: return null
        val need = m.groupValues[1]
        val cur = m.groupValues[2].ifEmpty { null }
        return Diagnosis(id, "Gradle is too old for this project's Android plugin", "Use Gradle $need and build again.",
            actions(FixAction("Use Gradle $need and build again", FixOp.UseGradle(need))),
            if (cur != null) "Needs Gradle $need, current is $cur" else "Needs Gradle $need")
    }
}

object SdkLicenseFix : QuickFix {
    override val id = "sdk-license"
    override fun detect(log: String, ctx: FixContext): Diagnosis? {
        if (!Regex("licen[cs]es have not been accepted", RegexOption.IGNORE_CASE).containsMatchIn(log)) return null
        return Diagnosis(id, "The Android SDK license has not been accepted", "Review and accept the license, then build again.",
            actions(FixAction("Review SDK license", FixOp.OpenLicense)))
    }
}

object ApiUnavailableFix : QuickFix {
    override val id = "api-unavailable"
    private val res = listOf(
        Regex("Android API (\\d+) is not available"),
        Regex("platforms;android-(\\d+) is not in the Google repository"),
        Regex("Could not preinstall Android API (\\d+)"),
    )
    override fun detect(log: String, ctx: FixContext): Diagnosis? {
        val api = res.firstNotNullOfOrNull { it.find(log)?.groupValues?.get(1)?.toIntOrNull() } ?: return null
        val best = ctx.maxInstalledPlatform?.takeIf { it < api } ?: ctx.maxKnownPlatform?.takeIf { it < api }
        return Diagnosis(id, "Android API $api is not available", "Lower Compile SDK to a version that exists, then build again.",
            actions(if (best != null) FixAction("Set Compile SDK to $best and build again", FixOp.SetCompileSdk(best)) else FixAction("Open SDK", FixOp.OpenSdk)),
            "Google does not publish API $api yet")
    }
}

object AarMetadataFix : QuickFix {
    override val id = "aar-compile-sdk"
    private val need = Regex("requires\\s+libraries\\s+and\\s+applications\\s+that\\s+depend\\s+on\\s+it\\s+to\\s+compile\\s+against\\s+version\\s+(\\d+)\\s+or\\s+later", RegexOption.IGNORE_CASE)
    private val dep = Regex("Dependency\\s+'([^']+)'\\s+requires")
    override fun detect(log: String, ctx: FixContext): Diagnosis? {
        val flat = log.replace(Regex("\\s+"), " ")
        val levels = need.findAll(flat).map { it.groupValues[1].toInt() }.toList()
        if (levels.isEmpty()) return null
        val max = levels.max()
        val deps = dep.findAll(flat).map { it.groupValues[1] }.distinct().toList()
        val canRaise = ctx.maxKnownPlatform != null && ctx.maxKnownPlatform >= max || ctx.maxInstalledPlatform != null && ctx.maxInstalledPlatform >= max
        val details = "A library needs Compile SDK $max or newer" + if (deps.isNotEmpty()) ": ${deps.take(3).joinToString()}" else ""
        return if (canRaise) Diagnosis(id, "A library needs a newer Compile SDK", "Raise Compile SDK to $max, or turn the check off (risky).",
            actions(FixAction("Set Compile SDK to $max and build again", FixOp.SetCompileSdk(max)), FixAction("Turn the check off (risky)", FixOp.DisableAarCheck)), details)
        else Diagnosis(id, "A library needs a newer Compile SDK than this device can get", "Turn the check off (risky), or use an older version of the library.",
            actions(FixAction("Turn the check off (risky)", FixOp.DisableAarCheck), FixAction("Open Dependencies", FixOp.OpenDeps)), details)
    }
}

object JdkMismatchFix : QuickFix {
    override val id = "jdk-mismatch"
    private val needs = Regex("requires Java (\\d+) to run\\. You are currently using Java (\\d+)")
    private val classFile = Regex("Unsupported class file major version (\\d+)")
    private val release = Regex("invalid source release: (\\d+)")
    override fun detect(log: String, ctx: FixContext): Diagnosis? {
        var want: Int? = null; var have: Int? = null
        needs.find(log)?.let { want = it.groupValues[1].toInt(); have = it.groupValues[2].toInt() }
        if (want == null) classFile.find(log)?.let { want = it.groupValues[1].toInt() - 44 }
        if (want == null) release.find(log)?.let { want = it.groupValues[1].toInt() }
        val unknown = log.contains("Could not determine java version from")
        if (want == null && !unknown) return null
        val w = want
        val details = when { w != null && have != null -> "Needs JDK $w, running JDK $have"; w != null -> "Needs JDK $w or newer"; else -> null }
        val act = if (ctx.hasAltJdk) FixAction("Use another JDK", FixOp.UseOtherJdk) else FixAction("Open SDK", FixOp.OpenSdk)
        return Diagnosis(id, "The JDK does not match this project", if (w != null) "Install JDK $w or newer in SDK, then build again." else "Install a standard JDK in SDK, then build again.", actions(act), details)
    }
}

object KotlinKspFix : QuickFix {
    override val id = "kotlin-ksp"
    private val old = Regex("ksp-([\\w.]+)-([\\w.]+) is too old for kotlin-([\\w.]+)")
    override fun detect(log: String, ctx: FixContext): Diagnosis? {
        val m = old.find(log)
        val hit = m != null || log.contains("Please upgrade ksp or downgrade kotlin-gradle-plugin") || Regex("KSP.*(?:supports|requires).*Kotlin", RegexOption.IGNORE_CASE).containsMatchIn(log)
        if (!hit) return null
        val details = if (m != null) "KSP ${m.groupValues[1]}-${m.groupValues[2]} does not match Kotlin ${m.groupValues[3].trimEnd('.')}" else null
        return Diagnosis(id, "The KSP and Kotlin versions do not match",
            "Use the KSP release made for your Kotlin version, or change the Kotlin version in Configuration.",
            actions(FixAction("Open Configuration", FixOp.OpenConfig)), details)
    }
}

object OutOfMemoryFix : QuickFix {
    override val id = "out-of-memory"
    override fun detect(log: String, ctx: FixContext): Diagnosis? {
        val kind = MemoryPlan.detect(log, ctx.exitCode)
        if (kind == OomKind.NONE) return null
        val a = MemoryPlan.advice(kind, ctx.heapMb, ctx.availRamMb, ctx.totalRamMb)
        val first = a.newHeapMb?.let { FixAction(if (it > ctx.heapMb) "Raise Gradle memory to $it MB and build again" else "Lower Gradle memory to $it MB and build again", FixOp.SetHeap(it)) }
        val second = if (a.disableKotlinDaemon) FixAction("Turn off the Kotlin daemon and build again", FixOp.DisableKotlinDaemon) else null
        return Diagnosis(id, a.title, a.steps.firstOrNull() ?: "Close other apps and build again", actions(first, second), a.steps.drop(1).joinToString(". ").ifEmpty { null })
    }
}

object DependencyFix : QuickFix {
    override val id = "dependency"
    private val network = listOf("UnknownHostException", "Network is unreachable", "Read timed out", "Connect timed out", "Connection reset", "No route to host", "Could not GET", "SocketTimeoutException")
    private val missing = Regex("Could not find ([\\w.-]+:[\\w.-]+:[\\w.+-]+)")
    override fun detect(log: String, ctx: FixContext): Diagnosis? {
        val hasNet = network.any { log.contains(it) }
        val m = missing.find(log)
        if (!hasNet && m == null && !log.contains("Could not resolve")) return null
        if (hasNet || (m == null)) return Diagnosis(id, "Dependencies could not be downloaded", "Check your internet connection and try again.",
            actions(FixAction("Try again", FixOp.RetryBuild), FixAction("Try again offline", FixOp.RetryOffline)))
        return Diagnosis(id, "A dependency was not found", "Check the name and version in Dependencies.",
            actions(FixAction("Open Dependencies", FixOp.OpenDeps), FixAction("Try again", FixOp.RetryBuild)), "Not found: ${m.groupValues[1].trimEnd('.')}")
    }
}

object QuickFixes {
    val all: List<QuickFix> = listOf(GradleTooOldFix, SdkLicenseFix, ApiUnavailableFix, AarMetadataFix, JdkMismatchFix, KotlinKspFix, OutOfMemoryFix, DependencyFix)

    /** Looks at "What went wrong" first (the real cause), then at the whole log. */
    fun diagnose(log: String, ctx: FixContext): Diagnosis? {
        val primary = LogTools.whatWentWrong(log)
        for (scope in listOf(primary, log)) for (f in all) f.detect(scope, ctx)?.let { return it }
        return null
    }
}

/** Edits the app build file so Gradle skips the checkAarMetadata task. Marked, idempotent and reversible. */
object AarCheckPatch {
    private const val START = "// AppBuilder: turns off the AAR metadata check (risky)"
    private const val END = "// AppBuilder: end"

    fun isApplied(text: String) = text.contains(START)

    fun apply(text: String, kts: Boolean): String {
        if (isApplied(text)) return text
        val block = if (kts) "$START\ntasks.configureEach {\n    if (name.endsWith(\"AarMetadata\")) { enabled = false }\n}\n$END\n"
        else "$START\ntasks.configureEach { task ->\n    if (task.name.endsWith(\"AarMetadata\")) { task.enabled = false }\n}\n$END\n"
        return text.trimEnd('\n') + "\n\n" + block
    }

    fun remove(text: String): String {
        val a = text.indexOf(START)
        if (a < 0) return text
        val b = text.indexOf(END, a).let { if (it < 0) text.length else it + END.length }
        return (text.substring(0, a).trimEnd('\n') + "\n" + text.substring(b).trimStart('\n')).trimEnd('\n') + "\n"
    }
}
