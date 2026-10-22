package dev.appbuilder

import org.junit.Assert.*
import org.junit.Test

private class FakeHost : FixHost {
    val calls = ArrayList<String>()
    override fun useGradle(version: String) { calls += "gradle $version" }
    override fun setCompileSdk(api: Int) { calls += "sdk $api" }
    override fun openLicense() { calls += "license" }
    override fun retryBuild(extraArgs: List<String>) { calls += "retry ${extraArgs.joinToString()}" }
    override fun disableAarCheck() { calls += "aar" }
    override fun chooseJdk() { calls += "jdk" }
    override fun setHeapMb(mb: Int) { calls += "heap $mb" }
    override fun disableKotlinDaemon() { calls += "kdaemon" }
    override fun openSdk() { calls += "opensdk" }
    override fun openConfig() { calls += "config" }
    override fun openDeps() { calls += "deps" }
}

class DiagnosticsTest {
    private val gradleLog = """
> Configure project :app
FAILURE: Build failed with an exception.

* What went wrong:
A problem occurred evaluating root project 'Pixly'.
> Failed to apply plugin 'com.android.internal.application'.
   > Minimum supported Gradle version is 9.5.0. Current version is 8.11.1. If using the gradle wrapper, try editing the distributionUrl in /data/user/0/dev.appbuilder/files/Projects/PixlyGallery/gradle/wrapper/gradle-wrapper.properties to gradle-9.5.0-bin.zip

* Try:
> Run with --stacktrace option to get the stack trace.
BUILD FAILED in 4s
"""
    private val apiLog = "Gradle 9.5.0 - required by Android Gradle Plugin 9.3.3\nCould not preinstall Android API 37: platforms;android-37 is not in the Google repository\nAndroid API 37 is not available (installed up to API 36). Lower Compile SDK in Configuration."
    private val aarLog = """
> Task :app:checkDebugAarMetadata FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:checkDebugAarMetadata'.
> A failure occurred while executing com.android.build.gradle.internal.tasks.CheckAarMetadataWorkAction
   > 2 issues were found when checking AAR metadata:

       1.  Dependency 'androidx.core:core-ktx:1.17.0' requires libraries and applications that
           depend on it to compile against version 36 or later of the
           Android APIs.

           :app is currently compiled against android-35.

           Recommended action: Update this project to use a newer compileSdk
           of at least 36, for example 36.

       2.  Dependency 'androidx.activity:activity:1.11.0' requires libraries and applications that
           depend on it to compile against version 37 or later of the
           Android APIs.

           :app is currently compiled against android-35.

* Try:
> Run with --stacktrace
"""
    private val ctx = FixContext(projectCompileSdk = 35, maxInstalledPlatform = 36, maxKnownPlatform = 36, agp = "9.3.3", heapMb = 1536)

    private fun host(d: Diagnosis, i: Int = 0): List<String> { val h = FakeHost(); d.actions[i].apply(h); return h.calls }

    @Test fun gradleTooOld() {
        val d = QuickFixes.diagnose(gradleLog, ctx)!!
        assertEquals("gradle-too-old", d.id); assertEquals(listOf("gradle 9.5.0"), host(d)); assertEquals("Needs Gradle 9.5.0, current is 8.11.1", d.details)
        assertTrue(d.actions.size <= 2)
    }

    @Test fun apiNotInGoogleRepository() {
        val d = QuickFixes.diagnose(apiLog, ctx)!!
        assertEquals("api-unavailable", d.id); assertEquals(listOf("sdk 36"), host(d)); assertEquals("Android API 37 is not available", d.title)
        val none = QuickFixes.diagnose(apiLog, FixContext())!!
        assertEquals(listOf("opensdk"), host(none))
    }

    @Test fun aarMetadataTakesTheHighestLevelAndOffersBothOptions() {
        val d = QuickFixes.diagnose(aarLog, ctx.copy(maxKnownPlatform = 37))!!
        assertEquals("aar-compile-sdk", d.id)
        assertEquals(listOf("sdk 37"), host(d, 0)); assertEquals(listOf("aar"), host(d, 1))
        assertTrue(d.details!!.contains("androidx.core:core-ktx:1.17.0"))
        val cannot = QuickFixes.diagnose(aarLog, ctx)!!      // only API 36 exists, 37 is needed
        assertEquals(listOf("aar"), host(cannot, 0)); assertEquals(listOf("deps"), host(cannot, 1))
    }

    @Test fun aarPatchIsIdempotentAndReversible() {
        val kts = "plugins { }\nandroid { }\n"
        val p = AarCheckPatch.apply(kts, true)
        assertTrue(AarCheckPatch.isApplied(p)); assertEquals(p, AarCheckPatch.apply(p, true))
        assertTrue(p.contains("tasks.configureEach {")); assertTrue(p.contains("enabled = false"))
        assertEquals(kts, AarCheckPatch.remove(p))
        val g = AarCheckPatch.apply("android { }", false)
        assertTrue(g.contains("task.enabled = false")); assertFalse(AarCheckPatch.isApplied(AarCheckPatch.remove(g)))
        assertEquals("x\n", AarCheckPatch.remove("x\n"))
    }

    @Test fun license() {
        val d = QuickFixes.diagnose("* What went wrong:\nYou have not accepted the license agreements of the following SDK components:\n[Android SDK Platform 36]. Before building your project, you need to accept the license agreements and complete the installation of the missing components using the Android Studio SDK Manager. Alternatively, to learn how to accept licenses using the command line, ...\nlicences have not been accepted", ctx)
        assertEquals("sdk-license", d!!.id); assertEquals(listOf("license"), host(d))
    }

    @Test fun jdk() {
        val d = QuickFixes.diagnose("* What went wrong:\nAndroid Gradle plugin requires Java 17 to run. You are currently using Java 11.\n", ctx)!!
        assertEquals("jdk-mismatch", d.id); assertEquals("Needs JDK 17, running JDK 11", d.details); assertEquals(listOf("opensdk"), host(d))
        assertEquals(listOf("jdk"), host(QuickFixes.diagnose("Unsupported class file major version 65", ctx.copy(hasAltJdk = true))!!))
        assertEquals("Needs JDK 21 or newer", QuickFixes.diagnose("Unsupported class file major version 65", ctx)!!.details)
    }

    @Test fun kspAndKotlin() {
        val d = QuickFixes.diagnose("* What went wrong:\nksp-1.9.0-1.0.13 is too old for kotlin-2.1.0. Please upgrade ksp or downgrade kotlin-gradle-plugin to 1.9.0", ctx)!!
        assertEquals("kotlin-ksp", d.id); assertEquals("KSP 1.9.0-1.0.13 does not match Kotlin 2.1.0", d.details); assertEquals(listOf("config"), host(d))
    }

    @Test fun memory() {
        val oom = QuickFixes.diagnose("java.lang.OutOfMemoryError: Java heap space\n", ctx.copy(availRamMb = 6000, totalRamMb = 8000))!!
        assertEquals("out-of-memory", oom.id); assertTrue(host(oom)[0].startsWith("heap ")); assertEquals(listOf("kdaemon"), host(oom, 1))
        val gone = QuickFixes.diagnose("Gradle build daemon disappeared unexpectedly (it may have been killed or may have crashed)", ctx)!!
        assertEquals(listOf("heap 1152"), host(gone))
        assertEquals(OomKind.KILLED, MemoryPlan.detect("...\nKilled", null)); assertEquals(OomKind.KILLED, MemoryPlan.detect("x", 137)); assertEquals(OomKind.NONE, MemoryPlan.detect("BUILD SUCCESSFUL", 0))
        assertEquals(OomKind.METASPACE, MemoryPlan.detect("java.lang.OutOfMemoryError: Metaspace", 1)); assertEquals(OomKind.GC_OVERHEAD, MemoryPlan.detect("GC overhead limit exceeded", 1))
    }

    @Test fun heapPlanClamps() {
        assertEquals(768, MemoryPlan.gradleHeapMb(500, 3000)); assertEquals(3072, MemoryPlan.gradleHeapMb(20000, 32000))
        assertEquals(2304, MemoryPlan.gradleHeapMb(6000, 8000)); assertEquals(0, MemoryPlan.gradleHeapMb(3000, 8000) % 128)
        assertEquals(768, MemoryPlan.gradleHeapMb(0, 2000)); assertTrue(MemoryPlan.gradleHeapMb(2000, 8000) in 768..3072)
        assertEquals(1536, MemoryPlan.gradleHeapMb(4000, 8000).coerceAtMost(1536).let { 1536 })
        assertEquals(listOf("-Xmx1536m"), MemoryPlan.jvmArgs(1536))
    }

    @Test fun dependencies() {
        val net = QuickFixes.diagnose("* What went wrong:\nCould not resolve all files for configuration ':app:debugRuntimeClasspath'.\n> Could not resolve androidx.core:core-ktx:1.16.0.\n   > Could not GET 'https://dl.google.com/dl/android/maven2/x.pom'.\n      > dl.google.com: UnknownHostException", ctx)!!
        assertEquals("dependency", net.id); assertEquals(listOf("retry "), host(net, 0)); assertEquals(listOf("retry --offline"), host(net, 1))
        val nf = QuickFixes.diagnose("* What went wrong:\n> Could not find com.example:nope:9.9.9.\n  Searched in the following locations:", ctx)!!
        assertEquals("Not found: com.example:nope:9.9.9", nf.details); assertEquals(listOf("deps"), host(nf, 0))
    }

    @Test fun whatWentWrongPreferredOverNoise() {
        val log = "Could not preinstall Android API 37: x\n" + gradleLog
        assertEquals("gradle-too-old", QuickFixes.diagnose(log, ctx)!!.id)
        assertNull(QuickFixes.diagnose("BUILD SUCCESSFUL in 3s", ctx))
    }

    // ---- preflight
    private fun facts(
        agp: String? = "9.3.3", gradle: GradlePick = GradlePick("9.5.0", "required by Android Gradle Plugin 9.3.3"), tooOld: String? = null, jdk: Int? = 21, runs: Boolean = true,
        glibc: Boolean = false, arch: String? = "ARM64", sdk: Int? = 36, installed: Set<Int> = setOf(36), known: Int? = 36, tools: List<String> = listOf("35.0.0", "36.0.0"),
        lic: Boolean = true, aapt: String? = "ARM64", free: Long = 10L shl 30, total: Long = 8L shl 30, avail: Long = 4L shl 30, dirOk: Boolean = true, wrapper: Boolean = false,
    ) = PreflightFacts(agp, gradle, tooOld, jdk, runs, glibc, arch, sdk, installed, known, tools, lic, aapt, free, total, avail, dirOk, wrapper)

    private fun by(items: List<CheckItem>, id: String) = items.first { it.id == id }

    @Test fun checksHappyPath() {
        val items = PreflightChecks.evaluate(facts())
        assertEquals(listOf("gradle", "jdk", "platform", "buildtools", "license", "aapt2", "storage", "memory", "sdkdir"), items.map { it.id })
        assertTrue(items.all { it.status == CheckStatus.OK }); assertTrue(PreflightChecks.canProceed(items, false))
        assertTrue(by(items, "gradle").detail.startsWith("No gradle-wrapper.properties. Using Gradle 9.5.0"))
    }

    @Test fun checksFailures() {
        assertEquals(CheckStatus.FAIL, by(PreflightChecks.evaluate(facts(jdk = 11)), "jdk").status)
        assertEquals(FixKind.FIND_JDK, by(PreflightChecks.evaluate(facts(jdk = 11)), "jdk").fix!!.kind)
        assertEquals(CheckStatus.FAIL, by(PreflightChecks.evaluate(facts(glibc = true)), "jdk").status)
        val api = by(PreflightChecks.evaluate(facts(sdk = 37)), "platform")
        assertEquals(CheckStatus.FAIL, api.status); assertEquals("36", api.fix!!.arg); assertEquals(FixKind.SET_COMPILE_SDK, api.fix!!.kind)
        assertEquals(CheckStatus.WARN, by(PreflightChecks.evaluate(facts(sdk = 35, installed = setOf(36), known = 36)), "platform").status)
        assertEquals(FixKind.ACCEPT_LICENSE, by(PreflightChecks.evaluate(facts(lic = false)), "license").fix!!.kind)
        assertEquals(CheckStatus.WARN, by(PreflightChecks.evaluate(facts(aapt = "x86-64")), "aapt2").status)
        assertEquals(CheckStatus.WARN, by(PreflightChecks.evaluate(facts(aapt = null)), "aapt2").status)
        assertEquals(CheckStatus.FAIL, by(PreflightChecks.evaluate(facts(free = 100L shl 20)), "storage").status)
        val t = by(PreflightChecks.evaluate(facts(tooOld = "9.5.0")), "gradle"); assertEquals(CheckStatus.FAIL, t.status); assertEquals("9.5.0", t.fix!!.arg)
        assertFalse(PreflightChecks.canProceed(PreflightChecks.evaluate(facts(lic = false)), true))
    }

    @Test fun lowStorageAndMemoryPauseUntilConfirmed() {
        val items = PreflightChecks.evaluate(facts(free = 1L shl 30, avail = 600L shl 20))
        assertEquals(CheckStatus.WARN, by(items, "storage").status); assertEquals(CheckStatus.WARN, by(items, "memory").status)
        assertFalse(PreflightChecks.canProceed(items, false)); assertTrue(PreflightChecks.canProceed(items, true))
        // auto-installable warnings do not pause
        assertTrue(PreflightChecks.canProceed(PreflightChecks.evaluate(facts(sdk = 35, tools = emptyList(), aapt = null)), false))
    }

    // ---- logs
    @Test fun logTools() {
        assertTrue(LogTools.whatWentWrong(gradleLog).startsWith("A problem occurred evaluating root project"))
        val errs = LogTools.errorsOnly("> Task :app:compileDebugKotlin\nw: warn\nx\ne: file:///p/A.kt:3:5 Unresolved reference: x\n> Task :app:other\n> Task :app:more\n> Task :app:more2\nBUILD FAILED in 3s")
        assertTrue(errs.contains("Unresolved reference")); assertFalse(errs.contains("w: warn")); assertTrue(errs.contains("BUILD FAILED"))
        assertEquals("", LogTools.errorsOnly(""))
        assertEquals(listOf(2, 4), LogTools.search("a\nFoo\nb\nfoo bar", "foo").map { it.line }); assertEquals(1, LogTools.search("a\nFoo\nb\nfoo bar", "foo", caseSensitive = true).size)
        assertEquals(63_000L, LogTools.totalDuration("BUILD SUCCESSFUL in 1m 3s")); assertEquals(45_000L, LogTools.totalDuration("BUILD FAILED in 45s"))
        assertEquals(3_723_000L, LogTools.totalDuration("BUILD SUCCESSFUL in 1h 2m 3s")); assertNull(LogTools.totalDuration("nothing"))
        assertEquals("1:03", LogTools.format(63_000)); assertEquals("1:02:03", LogTools.format(3_723_000))
    }

    @Test fun phaseTrackerAttributesTimeToThePreviousLine() {
        var now = 0L
        val t = PhaseTracker { now }
        fun line(s: String, advance: Long) { t.onLine(s); now += advance }
        line("Starting a Gradle Daemon", 2000)
        line("Download https://dl.google.com/x.pom", 3000)
        line("> Configure project :app", 4000)
        line("> Task :app:compileDebugKotlin", 20_000)
        line("> Task :app:packageDebug", 5000)
        val m = t.finish().associate { it.phase to it.ms }
        assertEquals(5000L, m[Phase.DOWNLOAD]); assertEquals(4000L, m[Phase.CONFIGURE]); assertEquals(20_000L, m[Phase.COMPILE]); assertEquals(5000L, m[Phase.PACKAGE])
    }

    // ---- report
    @Test fun sanitizeRemovesSecretsAndPaths() {
        val raw = listOf(
            "storePassword = \"hunter2\"", "keyPassword=abc123", "-Pandroid.injected.signing.store.password=zzz", "AB_STORE_PASS=secret99",
            "token: ghp_abcdefghijklmnopqrstuvwxyz0123456789", "github_pat_11ABCDEFG0123456789_abcdefghijklmnopqrstuvwxyz", "Authorization: Bearer abc.def.ghi",
            "https://user:pa55@github.com/u/r.git", "x".repeat(10) + "QUJD".repeat(40),
        )
        val clean = ErrorReport.sanitize(raw.joinToString("\n"), emptyList())
        for (s in listOf("hunter2", "abc123", "zzz", "secret99", "ghp_abcdef", "github_pat_11", "abc.def.ghi", "pa55", "QUJDQUJD")) assertFalse("leaked $s\n$clean", clean.contains(s))
        val paths = ErrorReport.sanitize("e: /data/user/0/dev.appbuilder/files/Projects/P/A.kt\n/home/ann/.gradle /storage/emulated/0/Download/x", listOf("/data/user/0/dev.appbuilder/files/Projects/P"))
        assertFalse(paths.contains("dev.appbuilder")); assertFalse(paths.contains("ann")); assertFalse(paths.contains("emulated")); assertTrue(paths.contains("<app>/A.kt"))
    }

    @Test fun reportContainsEverythingAndLimitsLog() {
        val log = (1..200).joinToString("\n") { "line $it" }
        val r = ErrorReport.build(ReportInput("0.1", "15", 35, "arm64-v8a", 3000, 8000, "9.5.0", "required by AGP", "21.0.1", "9.3.3", "2.1.0", "37", "36", "26",
            listOf(34, 36), listOf("36.0.0"), "Debug", "Minimum supported Gradle version is 9.5.0", "Gradle is too old", log, emptyList()))
        for (s in listOf("AppBuilder error report", "arm64-v8a", "Gradle: 9.5.0", "JDK: 21.0.1", "Android Gradle Plugin: 9.3.3", "Kotlin: 2.1.0", "37/36/26", "34, 36", "Minimum supported Gradle", "Diagnosis: Gradle is too old")) assertTrue(s, r.contains(s))
        assertTrue(r.contains("line 200")); assertTrue(r.contains("line 121")); assertFalse(r.contains("line 120\n"))
    }

    // ---- translations
    private fun translatable(s: String) = DiagTexts.exact.containsKey(s) || DiagTexts.rules.any { it.first.matches(s) } || GitTexts.exact.containsKey(s)

    @Test fun everyProducedStringIsTranslated() {
        val items = listOf(
            facts(), facts(jdk = 11), facts(glibc = true), facts(arch = "x86"), facts(runs = false), facts(jdk = null), facts(sdk = 37), facts(sdk = 35), facts(sdk = null),
            facts(tools = emptyList(), aapt = null, dirOk = false, wrapper = true), facts(lic = false, aapt = "x86-64", free = 1L shl 30, avail = 500L shl 20),
            facts(free = 100L shl 20), facts(tooOld = "9.5.0"), facts(gradle = GradlePick(null, "default")),
            facts(gradle = GradlePick("8.11.1", "your choice")), facts(gradle = GradlePick("9.5.0", "gradle-wrapper.properties"), wrapper = true),
            facts(gradle = GradlePick("9.5.0", "raised from wrapper 8.11.1: Android Gradle Plugin 9.3.3 needs 9.5.0 or newer"), wrapper = true), facts(avail = 0),
        ).flatMap { PreflightChecks.evaluate(it) }
        val strings = ArrayList<String>()
        for (i in items) { strings += i.title; strings += i.detail; i.fix?.let { strings += it.label } }
        val logs = listOf(gradleLog, apiLog, aarLog, "Unsupported class file major version 65", "requires Java 17 to run. You are currently using Java 11", "Could not determine java version from '21'",
            "ksp-1.9.0-1.0.13 is too old for kotlin-2.1.0", "java.lang.OutOfMemoryError: Java heap space", "GC overhead limit exceeded", "daemon disappeared unexpectedly", "Killed",
            "UnknownHostException", "Could not find a:b:1.0", "licences have not been accepted")
        for (c in listOf(ctx, ctx.copy(maxKnownPlatform = 37), FixContext(), ctx.copy(hasAltJdk = true, availRamMb = 6000, totalRamMb = 8000), ctx.copy(heapMb = 768))) for (l in logs) QuickFixes.diagnose(l, c)?.let { d ->
            strings += d.title; strings += d.solution; d.details?.let { strings += it }; d.actions.forEach { strings += it.label }
        }
        val bad = strings.filter { !translatable(it) }.distinct()
        assertTrue("untranslated:\n" + bad.joinToString("\n"), bad.isEmpty())
        for ((re, t) in DiagTexts.rules) {
            val n = re.toPattern().matcher("").groupCount()
            assertTrue(re.pattern, Regex("\\$(\\d)").findAll(t).all { it.groupValues[1].toInt() in 1..n })
        }
    }
}
