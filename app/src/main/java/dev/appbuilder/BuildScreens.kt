package dev.appbuilder

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun VersionManager(env: BuildEnvironment, kind: String, onClose: () -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val msgs by EnvStatus.messages.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var licenseNeeded by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<PkgEntry?>(null) }
    val list by produceState<PkgList?>(null, kind, tick) { value = withContext(Dispatchers.IO) { env.listPackages(kind) } }
    val title = when (kind) { "gradle" -> "Gradle versions"; "platform" -> "SDK Platform"; else -> "Build Tools" }
    fun key(e: PkgEntry) = "$kind-${e.id}"

    fun install(e: PkgEntry) {
        if (kind != "gradle" && !env.licensesAccepted()) { licenseNeeded = true; return }
        scope.launch {
            withContext(Dispatchers.IO) { env.runSafely(key(e)) { env.installPackage(kind, e.id) { m -> EnvStatus.set(key(e), m) } } }
            tick++
        }
    }

    Sheet(onClose) {
        Txt(title, T.section, modifier = Modifier.padding(20.dp))
        list?.takeIf { it.entries.isNotEmpty() }?.let { l0 ->
            val newest = l0.entries.first()
            Txt("Installed: ${l0.entries.count { it.installed }}", T.secondary, p.secondary, Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(10.dp))
            Box(Modifier.padding(horizontal = 20.dp)) {
                if (newest.installed) Txt("Latest installed", T.secondary, p.secondary)
                else Btn("Install latest ${newest.label}", { install(newest) }, Modifier.fillMaxWidth(), icon = I.download, accent = true)
            }
            Spacer(Modifier.height(10.dp))
        }
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            val l = list
            if (l == null) Txt("Loading...", T.body, p.secondary, Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            else {
                if (l.offline) Txt("Offline: showing installed versions only.", T.secondary, p.secondary, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                l.entries.forEach { e ->
                    val busy = msgs[key(e)]
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .tap({ if (busy == null || busy.startsWith("Failed")) action = e }, e.label).padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Txt(e.label, T.body, modifier = Modifier.weight(1f))
                        Txt(busy ?: if (e.installed) "Installed" else e.sizeMb?.let { "$it MB" } ?: "", T.secondary, if (busy?.startsWith("Failed") == true) p.error else p.secondary)
                    }
                    Divider()
                }
            }
        }
    }
    action?.let { e ->
        Sheet({ action = null }) {
            Txt(e.label, T.section, modifier = Modifier.padding(20.dp))
            if (e.installed) SheetItem("Remove", danger = true) {
                action = null
                scope.launch { withContext(Dispatchers.IO) { runCatching { env.removePackage(kind, e.id) } }; tick++ }
            } else SheetItem("Install") { action = null; install(e) }
        }
    }
    if (licenseNeeded) LicenseSheet(env, { licenseNeeded = false }, { licenseNeeded = false })
}

@Composable
fun LicenseSheet(env: BuildEnvironment, onDone: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    Sheet(onDismiss) {
        Txt("Android SDK license", T.section, modifier = Modifier.padding(20.dp))
        Txt(
            "Building downloads Android SDK packages from Google. They are covered by the Android Software Development Kit License Agreement (developer.android.com/studio/terms). Tap Accept only if you have read it and agree.",
            T.body, p.secondary, Modifier.padding(horizontal = 20.dp),
        )
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Cancel", onDismiss, Modifier.weight(1f), solid = false)
            Btn("Accept", { env.acceptLicenses(); onDone() }, Modifier.weight(1f))
        }
    }
}

@Composable
fun BuildScreen(
    project: Project, env: BuildEnvironment, start: Boolean, ready: Boolean,
    onBack: () -> Unit, onOpenSdk: () -> Unit, onResult: (BuildVariant) -> Unit, onError: (String, Int) -> Unit,
    onOpenConfig: () -> Unit = {}, onOpenDeps: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val s by BuildManager.state.collectAsState()
    LaunchedEffect(Unit) { if (start) BuildManager.start(ctx, project, env) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.running) { while (s.running) { now = System.currentTimeMillis(); delay(1000) } }
    val mine = s.project == project.name
    val running = s.running && mine
    val lastItem = remember(project) { History.read(listOf(project)).firstOrNull() }
    val lastSaved = lastItem?.result
    val shownVariant = BuildVariant.fromLabel(if (mine) s.variant else lastItem?.variant ?: "Debug")
    val prefs = remember { Prefs(ctx) }
    var variant by remember { mutableStateOf(BuildVariant.DEBUG) }
    var showSigning by remember { mutableStateOf(false) }
    var fresh by remember { mutableStateOf(ready) }
    val result = if (fresh && !running) null else if (mine) s.result else lastSaved
    var showLog by remember { mutableStateOf(!mine && lastSaved == "failed" && !ready) }
    var showLicense by remember { mutableStateOf(false) }
    val logText = if (mine && s.log.isNotEmpty()) s.log else if (project.log.exists()) project.log.readText().takeLast(30_000) else ""

    val scope = rememberCoroutineScope()
    val logSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val text = if (project.log.exists()) project.log.readText() else logText
            val ok = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }.isSuccess }
            Toast.makeText(ctx, if (ok) "Saved" else "Could not save the file", Toast.LENGTH_SHORT).show()
        }
    }
    var checks by remember { mutableStateOf<List<CheckItem>?>(null) }
    var pendingVariant by remember { mutableStateOf<BuildVariant?>(null) }
    var extra by remember { mutableStateOf<List<String>>(emptyList()) }
    var logFilter by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    val diagnosis = remember(result, s.log, s.message, s.heapMb) {
        if (result == "failed" && logText.isNotEmpty()) {
            val full = if (project.log.exists()) project.log.readText() else logText
            runCatching { QuickFixes.diagnose(full, PreflightRunner.fixContext(ctx, env, project.dir, null, s.heapMb.takeIf { it > 0 } ?: 1536)) }.getOrNull()
        } else null
    }
    if (showLicense) LicenseSheet(env, {
        showLicense = false
        Toast.makeText(ctx, "License accepted. Build again.", Toast.LENGTH_LONG).show()
    }, { showLicense = false })
    if (showSigning) SigningManager(env, prefs) { showSigning = false }
    fun launchBuild(args: List<String>) {
        checks = null
        BuildManager.start(ctx, project, env, variant, prefs.signing(), args)
        extra = emptyList()
    }
    val startBuild: () -> Unit = {
        fresh = false
        if (variant.release && prefs.signing() == null) {
            showSigning = true
        } else scope.launch {
            val items = withContext(Dispatchers.IO) { runCatching { PreflightChecks.evaluate(PreflightRunner.facts(ctx, env, project.dir)) }.getOrDefault(emptyList()) }
            if (PreflightChecks.canProceed(items, false)) launchBuild(extra)
            else checks = items
        }
    }
    val startBuildRef = remember { mutableStateOf<() -> Unit>({}) }
    startBuildRef.value = startBuild
    val fixHost = remember(project) {
        ProjectFixHost(project.dir, { args -> extra = args; startBuildRef.value() }, { showLicense = true }, onOpenSdk, onOpenConfig, onOpenDeps,
            { Toast.makeText(ctx, tr(it), Toast.LENGTH_LONG).show() })
    }
    fun onPreflightFix(f: CheckFix) {
        when (f.kind) {
            FixKind.OPEN_SDK, FixKind.FIND_JDK -> onOpenSdk()
            FixKind.ACCEPT_LICENSE -> showLicense = true
            FixKind.SET_COMPILE_SDK -> { f.arg?.let { GradleEdit.write(project.dir, mapOf("compileSdk" to it)) }; startBuild() }
            FixKind.USE_GRADLE -> { f.arg?.let { ProjectMeta.setGradleOverride(project.dir, it) }; startBuild() }
            FixKind.OPEN_CONFIG -> onOpenConfig()
            FixKind.INSTALL_PLATFORM, FixKind.INSTALL_BUILD_TOOLS -> onOpenSdk()
            FixKind.NONE -> {}
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(project.name, onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)) {
            checks?.let { items ->
                PreflightCard(items, { onPreflightFix(it) }, if (PreflightChecks.blocking(items).isEmpty()) { { launchBuild(extra) } } else null)
                Spacer(Modifier.height(20.dp))
            }
            when {
                s.running && !mine -> Txt("Another build is running (${s.project}).", T.body, p.secondary)
                running -> {
                    Txt("Building ${project.name}", T.title.copy(fontSize = 26.sp))
                    Spacer(Modifier.height(6.dp))
                    Txt("${s.variant} · ${clock(now - s.startedAt)}", T.body, p.secondary)
                    Spacer(Modifier.height(24.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).background(p.surface)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(s.progress.coerceIn(0.02f, 1f)).background(p.ink))
                    }
                    Spacer(Modifier.height(12.dp))
                    Txt(s.task.ifEmpty { "Starting Gradle" }, T.code, p.secondary, maxLines = 1)
                }
                result == "success" -> {
                    Txt("Build successful", T.title.copy(fontSize = 26.sp))
                    Spacer(Modifier.height(6.dp)); Txt(if (shownVariant == BuildVariant.DEBUG) "Debug APK" else shownVariant.label, T.body, p.secondary)
                }
                result == "cancelled" -> Txt("Build cancelled", T.title.copy(fontSize = 26.sp))
                result == "blocked" -> {
                    Txt("Build environment not ready", T.title.copy(fontSize = 26.sp))
                    Spacer(Modifier.height(10.dp)); Txt(s.message, T.body, p.secondary)
                    Spacer(Modifier.height(6.dp)); Txt("Install or import these components in SDK.", T.body, p.secondary)
                }
                result == "failed" -> {
                    Txt("Build failed", T.title.copy(fontSize = 26.sp))
                    Spacer(Modifier.height(6.dp))
                    Txt("${s.errors.size} errors · ${s.warnings} warnings", T.body, p.secondary)
                    diagnosis?.let { d -> Spacer(Modifier.height(16.dp)); DiagnosisCard(d, fixHost) }
                    Spacer(Modifier.height(16.dp)); Divider()
                    if (s.errors.isEmpty()) Txt(s.message.ifEmpty { "See the build log below." }, T.body, p.secondary, Modifier.padding(vertical = 14.dp))
                    s.errors.forEach { e ->
                        Column(Modifier.fillMaxWidth().tap({ onError(e.file, e.line) }, "Open ${e.file} line ${e.line}").padding(vertical = 12.dp)) {
                            Txt(e.file.substringAfterLast('/'), T.section, maxLines = 1)
                            Txt("Line ${e.line}", T.secondary, p.secondary)
                            Txt(e.text, T.code, p.ink)
                        }
                        Divider()
                    }
                }
                fresh -> {
                    Txt("Ready to build ${project.name}", T.title.copy(fontSize = 26.sp))
                    Spacer(Modifier.height(6.dp))
                    Txt("Choose what to build", T.body, p.secondary)
                }
                else -> Txt("No build yet.", T.body, p.secondary)
            }
            if (!running && mine && s.phases.isNotEmpty() && result != null && !fresh) {
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth().card().padding(16.dp)) {
                    Txt("Phases", T.secondary, p.secondary)
                    s.phases.forEach { ph ->
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            Txt(when (ph.phase) { Phase.DOWNLOAD -> "Download"; Phase.CONFIGURE -> "Configure"; Phase.COMPILE -> "Compile"; Phase.PACKAGE -> "Package"; Phase.OTHER -> "Other" }, T.body, modifier = Modifier.weight(1f))
                            Txt(LogTools.format(ph.ms), T.code, p.secondary)
                        }
                    }
                }
            }
            if (showLog || running) {
                Spacer(Modifier.height(20.dp))
                if (!running && logText.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip("All", logFilter == "All") { logFilter = "All" }
                        FilterChip("Errors only", logFilter != "All") { logFilter = "Errors" }
                    }
                    Spacer(Modifier.height(10.dp))
                    Field("Search log", query, { query = it })
                    Spacer(Modifier.height(10.dp))
                }
                val base = if (logFilter == "All") logText else LogTools.errorsOnly(logText)
                val hits = if (query.isBlank()) null else LogTools.search(base, query)
                if (hits != null) Txt(if (hits.isEmpty()) "No matches in the log" else "${hits.size} matches", T.secondary, p.secondary)
                Txt(hits?.joinToString("\n") { "${it.line}: ${it.text}" } ?: base.ifEmpty { "No log yet." }, T.code.copy(fontSize = 11.sp), p.secondary)
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!s.running) Segmented(listOf("Debug", "Release APK", "Release AAB"), variant.label, { variant = BuildVariant.fromLabel(it) })
            if (!s.running && variant.release && prefs.signing() == null) Txt("Release builds need a signing key. Create or import one first.", T.secondary, p.secondary)
            when {
                running -> Btn("Cancel build", { BuildManager.cancel() }, Modifier.fillMaxWidth())
                result == "success" -> {
                    Btn("View result", { onResult(shownVariant) }, Modifier.fillMaxWidth())
                    Btn(variant.button, { startBuild() }, Modifier.fillMaxWidth(), solid = false)
                }
                result == "blocked" -> Btn("Open SDK", onOpenSdk, Modifier.fillMaxWidth())
                !s.running -> Btn(variant.button, { startBuild() }, Modifier.fillMaxWidth(), accent = true)
            }
            if (result == "failed" && !running && logText.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val report = {
                    ErrorReport.build(ReportInput(
                        appVersion = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" }.getOrDefault(""),
                        androidVersion = android.os.Build.VERSION.RELEASE, sdkInt = android.os.Build.VERSION.SDK_INT, abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "",
                        availRamMb = 0, totalRamMb = 0,
                        gradle = Preflight.pickGradle(project.dir).version, gradleSource = Preflight.pickGradle(project.dir).reason,
                        jdk = null, agp = Toolchain.agpVersion(project.dir), kotlin = Toolchain.kotlinVersion(project.dir),
                        compileSdk = GradleEdit.read(project.dir)["compileSdk"], targetSdk = GradleEdit.read(project.dir)["targetSdk"], minSdk = GradleEdit.read(project.dir)["minSdk"],
                        installedPlatforms = runCatching { Preflight.installedPlatforms(env) }.getOrDefault(emptyList()), installedBuildTools = emptyList(),
                        variant = s.variant, whatWentWrong = LogTools.whatWentWrong(logText), diagnosisTitle = diagnosis?.title, log = logText,
                        privatePaths = listOf(project.dir.path, ctx.filesDir.path, env.homeDir().path),
                    ))
                }
                Btn("Copy error report", { copyText(ctx, "AppBuilder error report", report()); Toast.makeText(ctx, tr("Report copied"), Toast.LENGTH_SHORT).show() }, Modifier.weight(1f), solid = false)
                Btn("Share error report", { shareText(ctx, "AppBuilder error report", report()) }, Modifier.weight(1f), solid = false, icon = I.share)
            }
            if (!running && !fresh && logText.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn(if (showLog) "Hide build log" else "Open build log", { showLog = !showLog }, Modifier.weight(1f), solid = false)
                Btn("Save build log", { logSaver.launch("${project.dir.name}-build.log.txt") }, Modifier.weight(1f), solid = false, icon = I.upload)
            }
        }
    }
}

@Composable
fun LogScreen(item: HistoryItem, onBack: () -> Unit, onResult: () -> Unit, onError: (String, Int) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val text = remember(item) { if (item.logFile.exists()) item.logFile.readText() else "" }
    val errors = remember(text) { BuildManager.parseErrors(item.project, text) }
    val variant = BuildVariant.fromLabel(item.variant)
    val ok = item.result == "success"
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }.isSuccess }
            Toast.makeText(ctx, tr(if (saved) "Saved" else "Could not save the file"), Toast.LENGTH_SHORT).show()
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(item.project.name, onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            Txt(when (item.result) { "success" -> "Build successful"; "cancelled" -> "Build cancelled"; else -> "Build failed" }, T.title.copy(fontSize = 26.sp))
            Spacer(Modifier.height(6.dp))
            Txt("${item.variant} · ${timeAgo(item.at)} · ${clock(item.durationMs)}", T.secondary, p.secondary)
            if (errors.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth().card()) {
                    errors.forEachIndexed { i, e ->
                        Column(Modifier.fillMaxWidth().tap({ onError(e.file, e.line) }, "Open ${e.file} line ${e.line}").padding(16.dp)) {
                            Txt(e.file.substringAfterLast('/'), T.section, maxLines = 1)
                            Txt("Line ${e.line}", T.secondary, p.secondary)
                            Txt(e.text, T.code, p.ink)
                        }
                        if (i < errors.lastIndex) Divider()
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Txt("Build log", T.secondary, p.secondary)
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().card().padding(16.dp)) {
                Txt(text.takeLast(60_000).ifEmpty { "Log not available" }, T.code.copy(fontSize = 11.sp), if (text.isEmpty()) p.secondary else p.ink)
            }
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ok && item.project.output(variant).exists()) Btn("View result", onResult, Modifier.fillMaxWidth(), accent = true)
            if (text.isNotEmpty()) Btn("Save build log", { saver.launch("${item.project.dir.name}-${item.at}.log.txt") }, Modifier.fillMaxWidth(), solid = false, icon = I.upload)
        }
    }
}

@Composable
fun BuildsScreen(projects: List<Project>, onOpen: (HistoryItem) -> Unit) {
    val p = LocalPalette.current
    val history = remember(projects) { History.read(projects) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp)) { Txt("Builds", T.title.copy(fontSize = 32.sp)) }
        if (history.isEmpty()) Txt("No builds yet. Open a project and tap Run.", T.body, p.secondary, Modifier.padding(20.dp))
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(history) { h ->
                val ok = h.result == "success"
                Row(Modifier.fillMaxWidth().card().tap({ onOpen(h) }, "Open build of ${h.project.name}").padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Txt(h.project.name, T.section, maxLines = 1)
                        Txt("${h.variant} · " + when (h.result) { "success" -> "Build successful"; "cancelled" -> "Cancelled"; else -> "Build failed" } + " · ${clock(h.durationMs)}", T.secondary, p.secondary)
                    }
                    Txt(timeAgo(h.at), T.secondary, p.secondary)
                }
            }
        }
    }
}

@Composable
fun SdkScreen(env: BuildEnvironment, onImport: (EnvComponent) -> Unit, onImportLib: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val msgs by EnvStatus.messages.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<EnvComponent?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
    var licenseOpen by remember { mutableStateOf(false) }
    var urlFor by remember { mutableStateOf<EnvComponent?>(null) }
    var catalogOpen by remember { mutableStateOf(false) }
    val prefs = remember { Prefs(ctx) }
    var exportFor by remember { mutableStateOf<EnvComponent?>(null) }
    var manageFor by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val c = exportFor
        if (uri != null && c != null) scope.launch {
            EnvStatus.set(c.id, "Exporting")
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { env.exportComponent(c, it) } }.isSuccess
            }
            EnvStatus.set(c.id, null)
            Toast.makeText(ctx, tr(if (ok) "Package exported" else "Could not export"), Toast.LENGTH_LONG).show()
        }
    }
    var diagnosing by remember { mutableStateOf(false) }
    val comps = remember(refresh, msgs) { env.components() }

    fun op(c: EnvComponent, block: () -> Unit) {
        scope.launch { withContext(Dispatchers.IO) { env.runSafely(c.id, block) }; refresh++ }
    }

    fun downloadAll() {
        val all = env.components()
        val todo = all.filter { it.url != null && !env.installed(it) }
        if (todo.isEmpty()) {
            Toast.makeText(ctx, tr(if (all.all { env.installed(it) }) "Everything is already installed" else "Nothing to download. Set a source first."), Toast.LENGTH_LONG).show()
            return
        }
        scope.launch { withContext(Dispatchers.IO) { todo.forEach { c -> env.runSafely(c.id) { env.install(c) } } }; refresh++ }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp)) { Txt("SDK", T.title.copy(fontSize = 32.sp)) }
        Txt("Build environment", T.secondary, p.secondary, Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp))
        Column(Modifier.padding(horizontal = 20.dp).card()) { comps.forEach { c ->
            val busy = msgs[c.id]
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).tap({ selected = c }, c.title).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt(c.title, T.body)
                    if (c.url == null && !env.installed(c)) Txt("No download source. Import an archive.", T.secondary, p.secondary)
                }
                Txt(busy ?: if (env.installed(c)) "Installed" else "Not installed", T.secondary, if (busy?.startsWith("Failed") == true) p.error else p.secondary)
            }
            Divider()
        }
        }
        Txt("Android SDK Manager", T.secondary, p.secondary, Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp))
        Column(Modifier.padding(horizontal = 20.dp).card()) { listOf("platform" to "SDK Platform", "buildtools" to "Build Tools", "gradle" to "Gradle versions").forEach { (k, label) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).tap({ manageFor = k }, label).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(label, T.body, modifier = Modifier.weight(1f))
                Ico(I.chevron, tint = p.secondary, size = 18.dp)
            }
            Divider()
        } }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).tap({ licenseOpen = true }, "Android SDK license").padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("Android SDK license", T.body, modifier = Modifier.weight(1f))
            Txt(if (remember(refresh, licenseOpen) { env.licensesAccepted() }) "Accepted" else "Not accepted", T.secondary, p.secondary)
        }
        Divider()
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Download all", { downloadAll() }, Modifier.weight(1f), icon = I.download)
            Btn("Update catalog", { catalogOpen = true }, Modifier.weight(1f), solid = false)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.padding(horizontal = 16.dp)) {
            Btn(if (diagnosing) "Running..." else "Run diagnostics", {
                if (!diagnosing) { diagnosing = true; scope.launch { report = withContext(Dispatchers.IO) { try { env.diagnose() } catch (e: Exception) { "Diagnostics failed: ${e.message}" } }; diagnosing = false } }
            }, Modifier.fillMaxWidth(), solid = false, enabled = !diagnosing)
        }
        Txt("Archives must be built for Android (bionic) on your CPU, as .zip or .tar.gz. Sources can be set in:\n${env.catalogFile.path}", T.secondary, p.secondary, Modifier.padding(20.dp))
        Spacer(Modifier.height(100.dp))
    }

    if (licenseOpen) LicenseSheet(env, { licenseOpen = false; refresh++ }, { licenseOpen = false })
    manageFor?.let { VersionManager(env, it) { manageFor = null; refresh++ } }
    urlFor?.let { c ->
        var text by remember(c) { mutableStateOf(c.url ?: "") }
        Sheet({ urlFor = null }) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Download URL", text, { text = it.trim() }, mono = true, keyboard = KeyboardType.Uri)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Cancel", { urlFor = null }, Modifier.weight(1f), solid = false)
                    Btn("OK", {
                        if (text.startsWith("http")) { env.setSource(c.id, text); Toast.makeText(ctx, tr("Source saved"), Toast.LENGTH_SHORT).show(); urlFor = null; refresh++ }
                    }, Modifier.weight(1f))
                }
            }
        }
    }
    if (catalogOpen) {
        var text by remember { mutableStateOf(prefs.catalogUrl) }
        Sheet({ catalogOpen = false }) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Catalog URL", text, { text = it.trim() }, mono = true, keyboard = KeyboardType.Uri)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Cancel", { catalogOpen = false }, Modifier.weight(1f), solid = false)
                    Btn("Update catalog", {
                        val url = text
                        catalogOpen = false
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { runCatching { env.fetchCatalog(url) }.isSuccess }
                            if (ok) prefs.catalogUrl = url
                            Toast.makeText(ctx, tr(if (ok) "Catalog updated" else "Could not read the catalog"), Toast.LENGTH_LONG).show()
                            refresh++
                        }
                    }, Modifier.weight(1f))
                }
            }
        }
    }
    report?.let { r ->
        Sheet({ report = null }) {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(20.dp)) {
                Txt(r, T.code.copy(fontSize = 12.sp), p.ink)
            }
        }
    }
    selected?.let { c ->
        Sheet({ selected = null }) {
            Txt(c.title, T.section, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            if (c.url != null) SheetItem(if (env.installed(c)) "Repair (download again)" else "Install") {
                selected = null; op(c) { if (env.installed(c)) env.remove(c); env.install(c) }
            }
            if ((c.id == "jdk" || c.id == "buildtools") && env.installed(c)) SheetItem("Export package") {
                selected = null; exportFor = c; exporter.launch("${c.id}-android-arm64.zip")
            }
            if (c.id == "gradle" || c.id == "platform" || c.id == "buildtools") SheetItem("Manage versions") { selected = null; manageFor = c.id }
            SheetItem("Set download URL") { selected = null; urlFor = c }
            SheetItem("Import archive") { selected = null; onImport(c) }
            if (c.id == "jdk") SheetItem("Add library (.so)") { selected = null; onImportLib() }
            SheetItem("Verify") {
                selected = null
                Toast.makeText(ctx, if (env.installed(c)) "${c.title} is ready" else "${c.title}: ${c.check} not found", Toast.LENGTH_SHORT).show()
            }
            if (env.installed(c)) SheetItem("Remove", danger = true) { selected = null; op(c) { env.remove(c) } }
        }
    }
}

@Composable
private fun ConfigGroup(title: String, keys: List<String>, row: Boolean, values: MutableMap<String, String>, errors: Map<String, String>) {
    val present = keys.filter { values.containsKey(it) }
    if (present.isEmpty()) return
    val numeric = setOf("versionCode", "minSdk", "targetSdk", "compileSdk")
    Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Txt(title, T.secondary, LocalPalette.current.secondary)
        if (row) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            present.forEach { k ->
                Field(GradleEdit.labels[k] ?: k, values[k] ?: "", { values[k] = it }, Modifier.weight(1f), error = errors[k], keyboard = if (k in numeric) KeyboardType.Number else KeyboardType.Text)
            }
        } else present.forEach { k ->
            Field(GradleEdit.labels[k] ?: k, values[k] ?: "", { values[k] = it }, mono = k == "applicationId", error = errors[k])
        }
    }
}

@Composable
fun ConfigScreen(project: Project, repo: ProjectRepo, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val values = remember { mutableStateMapOf<String, String>().apply { putAll(GradleEdit.read(project.dir)) } }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Configuration", project.name, onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (values.isEmpty()) Txt("No editable Gradle settings were found in this project.", T.body, LocalPalette.current.secondary)
            ConfigGroup("Application", listOf("appName", "applicationId"), false, values, errors)
            ConfigGroup("Version", listOf("versionName", "versionCode"), true, values, errors)
            ConfigGroup("SDK", listOf("minSdk", "targetSdk", "compileSdk"), true, values, errors)
            ConfigGroup("Toolchain", listOf("kotlin", "gradle"), true, values, errors)
        }
        Box(Modifier.padding(16.dp)) {
            Btn("Save changes", {
                val e = GradleEdit.validate(values, repo)
                errors = e
                if (e.isEmpty()) {
                    GradleEdit.write(project.dir, values.toMap()); repo.touch(project)
                    Toast.makeText(ctx, tr("Saved"), Toast.LENGTH_SHORT).show(); onBack()
                }
            }, Modifier.fillMaxWidth(), enabled = values.isNotEmpty(), accent = true)
        }
    }
}

private val POPULAR = listOf(
    "AndroidX Core KTX" to "androidx.core:core-ktx:1.16.0",
    "AppCompat" to "androidx.appcompat:appcompat:1.7.0",
    "Material Components" to "com.google.android.material:material:1.12.0",
    "ConstraintLayout" to "androidx.constraintlayout:constraintlayout:2.2.1",
    "RecyclerView" to "androidx.recyclerview:recyclerview:1.4.0",
    "Lifecycle ViewModel" to "androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7",
    "Navigation Compose" to "androidx.navigation:navigation-compose:2.8.5",
    "Room Runtime" to "androidx.room:room-runtime:2.6.1",
    "Room KTX" to "androidx.room:room-ktx:2.6.1",
    "Retrofit" to "com.squareup.retrofit2:retrofit:2.11.0",
    "OkHttp" to "com.squareup.okhttp3:okhttp:4.12.0",
    "Gson" to "com.google.code.gson:gson:2.11.0",
    "Coil Compose" to "io.coil-kt:coil-compose:2.7.0",
    "Coroutines Android" to "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0",
)

@Composable
fun DepsScreen(project: Project, repo: ProjectRepo, onBack: () -> Unit) {
    val p = LocalPalette.current
    var deps by remember { mutableStateOf(Deps.list(project.dir)) }
    var editing by remember { mutableStateOf<Dep?>(null) }
    var adding by remember { mutableStateOf(false) }
    var popular by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    fun reload() { deps = Deps.list(project.dir); repo.touch(project) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Dependencies", project.name, onBack = onBack)
        Divider()
        if (deps.isEmpty()) Txt("No dependencies declared.", T.body, p.secondary, Modifier.padding(20.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(deps, key = { it.line }) { d ->
                Column(Modifier.fillMaxWidth().tap({ editing = d }, "Edit ${d.artifact}").padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Txt("${d.group}:${d.artifact}", T.code.copy(fontSize = 13.sp), maxLines = 1)
                    Txt(listOf(d.version.ifEmpty { "managed by BOM" }, d.config).joinToString(" · "), T.secondary, p.secondary)
                }
                Divider()
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Add dependency", { adding = true }, Modifier.weight(1f), icon = I.plus)
            Btn("Popular libraries", { popular = true }, Modifier.weight(1f), solid = false)
        }
    }

    if (popular) Sheet({ popular = false }) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            POPULAR.forEach { (label, coord) ->
                val (g, a, v) = coord.split(":")
                val have = deps.any { it.group == g && it.artifact == a }
                SheetItem(if (have) "$label · ✓" else "$label · $v") {
                    if (!have) { Deps.add(project.dir, g, a, v); reload(); Toast.makeText(ctx, tr("Added"), Toast.LENGTH_SHORT).show() }
                    popular = false
                }
            }
        }
    }
    if (adding) {
        var g by remember { mutableStateOf("") }; var a by remember { mutableStateOf("") }; var v by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        Sheet({ adding = false }) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Group", g, { g = it.trim() }, mono = true)
                Field("Artifact", a, { a = it.trim() }, mono = true)
                Field("Version", v, { v = it.trim() }, mono = true, error = err)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Cancel", { adding = false }, Modifier.weight(1f), solid = false)
                    Btn("Add", {
                        err = Deps.validate(g, a, v)
                        if (err == null) { Deps.add(project.dir, g, a, v); adding = false; reload() }
                    }, Modifier.weight(1f))
                }
            }
        }
    }
    editing?.let { d ->
        var v by remember(d) { mutableStateOf(d.version) }
        Sheet({ editing = null }) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt("${d.group}:${d.artifact}", T.code.copy(fontSize = 13.sp))
                Field("Version", v, { v = it.trim() }, mono = true)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Remove", { Deps.remove(project.dir, d); editing = null; reload() }, Modifier.weight(1f), solid = false)
                    Btn("Update", {
                        if (Deps.validate(d.group, d.artifact, v) == null) { Deps.update(project.dir, d, v); editing = null; reload() }
                    }, Modifier.weight(1f))
                }
            }
        }
    }
}
