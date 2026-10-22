package dev.appbuilder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

data class BuildError(val file: String, val line: Int, val text: String)

data class BuildState(
    val running: Boolean = false,
    val project: String = "",
    val variant: String = "Debug",
    val task: String = "",
    val progress: Float = 0f,
    val startedAt: Long = 0,
    val log: String = "",
    val errors: List<BuildError> = emptyList(),
    val warnings: Int = 0,
    val result: String? = null, // success | failed | cancelled | blocked
    val message: String = "",
    val phases: List<PhaseTime> = emptyList(),
    val heapMb: Int = 0,
)

/**
 * Runs Gradle in the background, independent of the UI. The UI only observes [state].
 * Gradle is launched through the JVM directly (GradleMain), not through the shell script,
 * because Android has no /bin/sh for the script's shebang.
 */
object BuildManager {
    val state = MutableStateFlow(BuildState())
    @Volatile private var process: Process? = null
    @Volatile private var cancelled = false

    private val taskRe = Regex("^> Task (:\\S+)")
    private val kotlinErr = Regex("^e: (?:file://)?(.+?):(\\d+):(\\d+)\\s*(.*)$")
    private val javaErr = Regex("^(.+\\.java):(\\d+): error: (.*)$")
    private val xmlErr = Regex("^(?:ERROR:)?\\s*(.+\\.xml):(\\d+)(?::\\d+)?:?\\s*(?:error:)?\\s*(.*)$")

    fun cancel() { cancelled = true; process?.destroy() }

    fun start(ctx: Context, p: Project, env: BuildEnvironment, variant: BuildVariant = BuildVariant.DEBUG, signing: Signing? = null, extraArgs: List<String> = emptyList()) {
        if (state.value.running) return
        if (variant.release && signing == null) {
            state.value = BuildState(project = p.name, variant = variant.label, result = "blocked", message = "Release builds need a signing key. Create or import one first.")
            return
        }
        val miss = env.missing()
        if (miss.isNotEmpty()) {
            state.value = BuildState(project = p.name, result = "blocked", message = "Missing: " + miss.joinToString { it.title })
            return
        }
        val app = ctx.applicationContext
        cancelled = false
        state.value = BuildState(running = true, project = p.name, variant = variant.label, startedAt = System.currentTimeMillis())
        File(p.dir, RUNNING).writeText("1")
        ContextCompat.startForegroundService(app, Intent(app, BuildService::class.java).putExtra("name", p.name))
        Thread { execute(app, p, env, variant, signing, extraArgs) }.apply { name = "build-${p.name}"; start() }
    }

    private fun execute(app: Context, p: Project, env: BuildEnvironment, variant: BuildVariant, signing: Signing?, extraArgs: List<String> = emptyList()) {
        val started = System.currentTimeMillis()
        val full = StringBuilder()
        val errors = LinkedHashSet<BuildError>()
        var warnings = 0; var tasks = 0; var lastEmit = 0L; var lastNotify = 0L; var current = ""
        fun notifyProgress() {
            // A percentage in the task text (a download) is exact; otherwise estimate from finished Gradle tasks.
            val textPct = Regex("(\\d+)%\\s*$").find(current)?.groupValues?.get(1)?.toIntOrNull()
            val pct = textPct ?: if (tasks > 0) tasks * 100 / (tasks + 10) else null
            BuildService.progress(app, tr("Building ${p.name}"), tr(current.ifEmpty { "Starting Gradle" }), pct, true, 1)
        }
        fun emit(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastEmit < 150) return
            lastEmit = now
            state.value = state.value.copy(
                task = current, progress = tasks / (tasks + 10f).coerceAtLeast(1f),
                log = full.takeLast(30_000).toString(), errors = errors.toList(), warnings = warnings,
            )
            if (force || now - lastNotify > 1000) { lastNotify = now; notifyProgress() }
        }
        var exit = -1
        val phases = PhaseTracker()
        var heapMb = 0
        try {
            File(p.dir, "local.properties").writeText("sdk.dir=${env.sdkRoot().path}\n")
            val java = File(env.javaHome(), "bin/java").path
            val pick = Preflight.pickGradle(p.dir)
            val wanted = pick.version
            full.append("Gradle ${wanted ?: "(default)"} - ${pick.reason}\n")
            val gradleDir = env.ensureGradle(wanted) { msg -> current = msg; emit(true) }
            // Best effort: if this fails, the Android Gradle Plugin still tries on its own.
            GradleEdit.read(p.dir)["compileSdk"]?.toIntOrNull()?.let { api ->
                if (env.licensesAccepted() && !env.platformInstalled(api)) {
                    try { env.installPackage("platform", api.toString()) { msg -> current = msg; emit(true) } }
                    catch (e: Exception) {
                        full.append("Could not preinstall Android API $api: ${e.message}\n")
                        // Google does not publish this API level, so Gradle cannot get it either: stop here with a clear reason.
                        if (e.message?.contains("is not in the Google repository") == true) {
                            val best = Preflight.maxInstalledPlatform(env)
                            throw IllegalStateException("Android API $api is not available (installed up to API ${best ?: "none"}). Lower Compile SDK in Configuration.")
                        }
                    }
                }
            }
            val launcher = File(gradleDir, "lib").listFiles { f -> f.name.startsWith("gradle-launcher-") && f.name.endsWith(".jar") }?.firstOrNull()
                ?: throw IllegalStateException("Gradle launcher jar not found")
            env.ensureExecutables()
            if (env.missingLibs().isNotEmpty()) env.fixMissingLibs()
            env.missingLibs().takeIf { it.isNotEmpty() }?.let {
                throw IllegalStateException("Missing libraries: ${it.joinToString()}. Run diagnostics in SDK, or add them with JDK > Add library (.so).")
            }
            if (env.isGlibcBinary(File(java))) throw IllegalStateException("This JDK is a Linux (glibc) build and cannot run on Android. Use an Android (bionic) JDK build.")
            val javaArch = env.elfArch(File(java))
            if (javaArch != "ARM64") throw IllegalStateException("JDK binary is $javaArch, expected ARM64")
            env.aapt2()?.let { a ->
                val arch = env.elfArch(a)
                if (arch != "ARM64") throw IllegalStateException("aapt2 is $arch and cannot run on this phone. Import ARM64 build tools.")
            }
            env.writeGradleUserProperties()
            val mi = ActivityManager.MemoryInfo().also { (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
            heapMb = ProjectMeta.heapOverrideMb(p.dir) ?: MemoryPlan.gradleHeapMb(mi.availMem shr 20, mi.totalMem shr 20)
            full.append("Gradle memory: $heapMb MB\n")
            val cmd = mutableListOf(
                java, *MemoryPlan.jvmArgs(heapMb).toTypedArray(), "-Djdk.lang.Process.launchMechanism=FORK", "-Dfile.encoding=UTF-8",
                "-Djava.io.tmpdir=${env.tmpDir().path}", "-Duser.home=${env.homeDir().path}",
                "-Dorg.gradle.appname=gradle", "-classpath", launcher.path,
                "org.gradle.launcher.GradleMain", "--console=plain", "--no-daemon", "--stacktrace", "-Dorg.gradle.native=false", variant.task,
            )
            env.aapt2()?.let { cmd.add("-Pandroid.aapt2FromMavenOverride=${it.path}") }
            if (ProjectMeta.kotlinInProcess(p.dir)) cmd.addAll(MemoryPlan.kotlinInProcessArgs())
            if (ProjectMeta.offline(p.dir) || extraArgs.contains("--offline")) cmd.add("--offline")
            val pb = ProcessBuilder(cmd).directory(p.dir).redirectErrorStream(true)
            env.applyProcessEnv(pb)
            if (variant.release && signing != null) pb.environment().apply {
                put("AB_STORE", signing.file.path); put("AB_STORE_PASS", signing.password)
                put("AB_KEY_ALIAS", signing.alias); put("AB_KEY_PASS", signing.password)
            }
            val proc = pb.start().also { process = it }
            proc.inputStream.bufferedReader().forEachLine { line ->
                phases.onLine(line)
                if (full.length < 2_000_000) full.append(line).append('\n')
                taskRe.find(line)?.let { current = it.groupValues[1]; tasks++ }
                if (line.startsWith("w: ") || line.contains("warning:")) warnings++
                parseError(p, line)?.let { errors += it }
                emit()
            }
            exit = proc.waitFor()
        } catch (e: Exception) {
            full.append("\n").append(e.message ?: e.toString()).append('\n')
        } finally { process = null }

        val result = when {
            cancelled -> "cancelled"
            exit == 0 -> "success"
            else -> "failed"
        }
        val duration = System.currentTimeMillis() - started
        if (result == "failed") LearnedStore.learn(full.toString(), Toolchain.agpVersion(p.dir))
        val at = System.currentTimeMillis()
        p.log.writeText(full.toString())
        archiveLog(p.dir, at, full.toString())
        File(p.dir, HISTORY).appendText("$at|${variant.label}|$result|$duration\n")
        setLastBuild(p.dir, result == "success")
        File(p.dir, RUNNING).delete()
        current = if (result == "success") "" else current
        emit(true)
        state.value = state.value.copy(
            running = false, result = result, progress = if (result == "success") 1f else state.value.progress,
            message = if (errors.isEmpty() && result == "failed") whatWentWrong(full.toString()) else "",
            phases = phases.finish(), heapMb = heapMb,
        )
        // Never stopService() directly: if it races ahead of onStartCommand, Android kills the app with
        // "startForegroundService() did not then call startForeground()". The service stops itself instead.
        ContextCompat.startForegroundService(app, Intent(app, BuildService::class.java).setAction(BuildService.ACTION_STOP))
        when (result) {
            "success" -> BuildService.notifyResult(app, tr("${p.name} built successfully"), tr("${variant.label} is ready"))
            "failed" -> BuildService.notifyResult(app, tr("Build failed"), tr("Tap to view errors"))
        }
    }

    /** Every build keeps its own log (newest 30), so a failed build can be opened again after later builds. */
    private fun archiveLog(dir: File, at: Long, text: String) {
        val d = File(dir, ".appbuilder-logs").apply { mkdirs() }
        File(d, "$at.log").writeText(text.takeLast(400_000))
        d.listFiles()?.sortedByDescending { it.name }?.drop(30)?.forEach { it.delete() }
    }

    fun parseErrors(p: Project, log: String): List<BuildError> {
        val out = LinkedHashSet<BuildError>()
        log.lineSequence().forEach { line -> parseError(p, line)?.let { out += it } }
        return out.toList()
    }

    /** Gradle prints the real cause under "* What went wrong:". Show that, not the last line of the log. */
    private fun whatWentWrong(log: String): String = LogTools.whatWentWrong(log)

    private fun parseError(p: Project, line: String): BuildError? {
        fun rel(path: String) = path.removePrefix(p.dir.path + "/").removePrefix(p.dir.canonicalPath + "/")
        kotlinErr.find(line)?.let { return BuildError(rel(it.groupValues[1]), it.groupValues[2].toInt(), it.groupValues[4]) }
        javaErr.find(line)?.let { return BuildError(rel(it.groupValues[1]), it.groupValues[2].toInt(), it.groupValues[3]) }
        if (line.contains("error:") || line.startsWith("ERROR:")) xmlErr.find(line)?.let {
            return BuildError(rel(it.groupValues[1]), it.groupValues[2].toInt(), it.groupValues[3])
        }
        return null
    }

    /** Set when the user accepts "Use Gradle X" after a version error. Takes priority over the wrapper file. */
    fun gradleOverride(dir: File): String? = ProjectMeta.gradleOverride(dir)

    fun setGradleOverride(dir: File, version: String) = ProjectMeta.setGradleOverride(dir, version)

    private fun setLastBuild(dir: File, ok: Boolean) = ProjectMeta.setLastBuild(dir, ok)
}

data class HistoryItem(val project: Project, val variant: String, val result: String, val at: Long, val durationMs: Long) {
    val logFile: File get() = File(project.dir, ".appbuilder-logs/$at.log")
}

object History {
    fun read(projects: List<Project>): List<HistoryItem> = projects.flatMap { p ->
        val f = File(p.dir, HISTORY)
        if (!f.exists()) emptyList() else f.readLines().mapNotNull { l ->
            val s = l.split('|')
            if (s.size < 4) null else HistoryItem(p, s[1], s[2], s[0].toLongOrNull() ?: 0, s[3].toLongOrNull() ?: 0)
        }
    }.sortedByDescending { it.at }.take(60)
}

class BuildService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels(this)
        if (intent?.action == ACTION_CANCEL) {
            BuildManager.cancel()
            if (!BuildManager.state.value.running) stopSelf()
            return START_NOT_STICKY
        }
        val stopping = intent?.action == ACTION_STOP
        val n = Notification.Builder(this, CH_BUILD)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Building ${intent?.getStringExtra("name") ?: ""}")
            .setContentText("Build in progress")
            .setOngoing(true)
            .setContentIntent(openApp(this))
            .build()
        startForeground(1, n) // always, even when about to stop: this is what Android requires
        if (stopping) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_STOP = "dev.appbuilder.STOP_BUILD"
        const val ACTION_CANCEL = "dev.appbuilder.CANCEL_BUILD"
        const val CH_BUILD = "build"
        const val CH_RESULT = "build_result"

        fun ensureChannels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_BUILD, "Build progress", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_RESULT, "Build results", NotificationManager.IMPORTANCE_DEFAULT))
        }

        /** Ongoing notification with a progress bar. pct == null shows an indeterminate bar. */
        fun progress(c: Context, title: String, text: String, pct: Int?, cancelable: Boolean, id: Int) {
            ensureChannels(c)
            val b = Notification.Builder(c, CH_BUILD)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title).setContentText(text)
                .setOngoing(true).setOnlyAlertOnce(true)
                .setProgress(100, (pct ?: 0).coerceIn(0, 100), pct == null)
                .setContentIntent(openApp(c))
            if (cancelable) {
                val pi = PendingIntent.getService(c, 7, Intent(c, BuildService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
                b.addAction(Notification.Action.Builder(Icon.createWithResource(c, android.R.drawable.ic_menu_close_clear_cancel), tr("Cancel"), pi).build())
            }
            c.getSystemService(NotificationManager::class.java).notify(id, b.build())
        }

        fun clear(c: Context, id: Int) = c.getSystemService(NotificationManager::class.java).cancel(id)

        fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )

        fun notifyResult(c: Context, title: String, text: String) {
            ensureChannels(c)
            val n = Notification.Builder(c, CH_RESULT)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title).setContentText(text)
                .setAutoCancel(true).setContentIntent(openApp(c)).build()
            c.getSystemService(NotificationManager::class.java).notify(2, n)
        }
    }
}
