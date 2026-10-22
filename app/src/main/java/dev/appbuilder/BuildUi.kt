package dev.appbuilder

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.StatFs
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

/** Gathers the device and project facts the pre-build checklist needs. Blocking: call off the main thread. */
object PreflightRunner {
    fun facts(ctx: Context, env: BuildEnvironment, dir: File): PreflightFacts {
        val java = File(env.javaHome(), "bin/java")
        val release = File(env.javaHome(), "release").takeIf { it.exists() }?.readText().orEmpty()
        val mi = ActivityManager.MemoryInfo().also { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        val agp = Toolchain.agpVersion(dir)
        val override = ProjectMeta.gradleOverride(dir)
        val pick = Preflight.pickGradle(dir)
        val installed = runCatching { Preflight.installedPlatforms(env).toSet() }.getOrDefault(emptySet())
        val tools = File(env.sdkRoot(), "build-tools").listFiles { f -> f.isDirectory }?.map { it.name }.orEmpty()
        val arch = if (java.exists()) env.elfArch(java) else null
        return PreflightFacts(
            agp = agp, gradle = pick,
            wrapperOverrideTooOld = Toolchain.overrideTooOld(override, agp),
            jdkMajor = Toolchain.jdkMajor(Regex("JAVA_VERSION=\"([^\"]+)\"").find(release)?.groupValues?.get(1) ?: ""),
            jdkRuns = java.exists(), jdkIsGlibc = java.exists() && env.isGlibcBinary(java), jdkArch = arch,
            compileSdk = GradleEdit.read(dir)["compileSdk"]?.toIntOrNull(),
            installedPlatforms = installed, maxKnownPlatform = runCatching { Preflight.maxKnownPlatform(env) }.getOrNull(),
            installedBuildTools = tools, licensesAccepted = env.licensesAccepted(),
            aapt2Arch = env.aapt2()?.let { env.elfArch(it) },
            freeBytes = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE),
            totalRamBytes = mi.totalMem, availRamBytes = mi.availMem, sdkDirOk = File(dir, "local.properties").exists(),
            hasWrapperProps = File(dir, "gradle/wrapper/gradle-wrapper.properties").exists(),
        )
    }

    fun fixContext(ctx: Context, env: BuildEnvironment, dir: File, exit: Int?, heapMb: Int): FixContext {
        val mi = ActivityManager.MemoryInfo().also { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        return FixContext(
            projectCompileSdk = GradleEdit.read(dir)["compileSdk"]?.toIntOrNull(),
            maxInstalledPlatform = Preflight.maxInstalledPlatform(env), maxKnownPlatform = Preflight.maxKnownPlatform(env),
            agp = Toolchain.agpVersion(dir), kotlin = Toolchain.kotlinVersion(dir),
            heapMb = heapMb, exitCode = exit, availRamMb = mi.availMem shr 20, totalRamMb = mi.totalMem shr 20,
        )
    }
}

/** Applies fixes for a project. The screen supplies navigation and the rebuild. */
class ProjectFixHost(
    private val dir: File, private val rebuild: (List<String>) -> Unit,
    private val onLicense: () -> Unit, private val onSdk: () -> Unit, private val onConfig: () -> Unit, private val onDeps: () -> Unit,
    private val onMessage: (String) -> Unit,
) : FixHost {
    override fun useGradle(version: String) { ProjectMeta.setGradleOverride(dir, version); rebuild(emptyList()) }
    override fun setCompileSdk(api: Int) { GradleEdit.write(dir, mapOf("compileSdk" to api.toString())); rebuild(emptyList()) }
    override fun openLicense() = onLicense()
    override fun retryBuild(extraArgs: List<String>) = rebuild(extraArgs)
    override fun disableAarCheck() {
        val f = listOf("app/build.gradle.kts", "app/build.gradle").map { File(dir, it) }.firstOrNull { it.exists() } ?: return
        f.writeText(AarCheckPatch.apply(f.readText(), f.name.endsWith(".kts")))
        onMessage("Turned off the AAR metadata check"); rebuild(emptyList())
    }
    override fun chooseJdk() = onSdk()
    override fun setHeapMb(mb: Int) { ProjectMeta.setHeapOverrideMb(dir, mb); rebuild(emptyList()) }
    override fun disableKotlinDaemon() { ProjectMeta.setKotlinInProcess(dir, true); onMessage("Kotlin daemon turned off for this project"); rebuild(emptyList()) }
    override fun openSdk() = onSdk()
    override fun openConfig() = onConfig()
    override fun openDeps() = onDeps()
}

private fun statusMark(s: CheckStatus) = when (s) { CheckStatus.OK -> "OK"; CheckStatus.WARN -> "!"; CheckStatus.FAIL -> "X" }

/** One summary card above the log: cause, one sentence of solution, at most two buttons. */
@Composable
fun DiagnosisCard(d: Diagnosis, host: FixHost, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(modifier.fillMaxWidth().card().padding(16.dp)) {
        Txt("Why did it fail?", T.secondary, p.secondary)
        Spacer(Modifier.height(6.dp))
        Txt(d.title, T.section)
        d.details?.let { Spacer(Modifier.height(4.dp)); Txt(it, T.code, p.secondary) }
        Spacer(Modifier.height(6.dp))
        Txt(d.solution, T.body, p.secondary)
        if (d.actions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                d.actions.take(2).forEachIndexed { i, a -> Btn(a.label, { a.apply(host) }, Modifier.weight(1f), solid = i == 0, accent = i == 0) }
            }
        }
    }
}

/** The pre-build checklist. Shown only when something needs attention, so a healthy setup builds with no extra tap. */
@Composable
fun PreflightCard(items: List<CheckItem>, onFix: (CheckFix) -> Unit, onContinue: (() -> Unit)?, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(modifier.fillMaxWidth().card().padding(16.dp)) {
        Txt("Pre-build check", T.section)
        Spacer(Modifier.height(4.dp))
        if (items.any { it.status == CheckStatus.FAIL }) Txt("Fix the items marked in red to build.", T.secondary, p.secondary)
        Spacer(Modifier.height(8.dp))
        items.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                Txt(statusMark(c.status), T.code, if (c.status == CheckStatus.FAIL) p.error else p.secondary, Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Txt(c.title, T.body)
                    Txt(c.detail, T.secondary, if (c.status == CheckStatus.FAIL) p.error else p.secondary)
                    c.fix?.let { f -> if (c.status != CheckStatus.OK) { Spacer(Modifier.height(6.dp)); Btn(f.label, { onFix(f) }, Modifier.fillMaxWidth(), solid = false) } }
                }
            }
        }
        if (onContinue != null) { Spacer(Modifier.height(8.dp)); Btn("Continue anyway", onContinue, Modifier.fillMaxWidth(), solid = false) }
    }
}

fun shareText(ctx: Context, title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); putExtra(Intent.EXTRA_SUBJECT, title) }
    ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun copyText(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
}
