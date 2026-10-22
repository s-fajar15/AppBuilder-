package dev.appbuilder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val TEXT_EXT = setOf("kt", "java", "xml", "gradle", "kts", "properties", "json", "toml", "md", "txt", "pro", "gitignore")

private class Node(val file: File, val depth: Int)

private fun flatten(dir: File, depth: Int, expanded: List<String>, out: MutableList<Node>) {
    dir.listFiles()
        ?.filter { !it.name.startsWith(".appbuilder") && it.name != ".git" && !it.name.endsWith(".tmp") }
        ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        ?.forEach { f ->
            out += Node(f, depth)
            if (f.isDirectory && f.path in expanded) flatten(f, depth + 1, expanded, out)
        }
}

private sealed interface Prompt {
    class NewFile(val dir: File) : Prompt
    class Rename(val file: File) : Prompt
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProjectScreen(
    project: Project, repo: ProjectRepo, fontSize: Int, wrap: Boolean, onWrap: () -> Unit, onFontSize: (Int) -> Unit,
    openAt: Pair<String, Int>?, onBack: () -> Unit, onRun: () -> Unit, onExport: () -> Unit, onDeleted: () -> Unit,
    onConfig: () -> Unit, onDeps: () -> Unit, onGit: () -> Unit = {}, onPreview: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val open = remember { mutableStateListOf<OpenFile>() }
    var active by remember { mutableIntStateOf(-1) }
    var showTree by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var nodeMenu by remember { mutableStateOf<File?>(null) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    var confirm by remember { mutableStateOf<File?>(null) }
    var confirmProject by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val expanded = remember { mutableStateListOf(File(project.dir, "app").path) }

    fun saveAll() {
        open.filter { it.dirty }.forEach { o -> saveAtomic(o.file, o.value.text); o.saved = o.value.text; repo.touch(project) }
    }
    DisposableEffect(Unit) { onDispose { saveAll() } }

    fun openFile(f: File) {
        val i = open.indexOfFirst { it.file == f }
        if (i >= 0) { active = i; showTree = false; return }
        if (f.length() > 1_000_000 || (f.extension.lowercase() !in TEXT_EXT)) {
            Toast.makeText(ctx, "This file type cannot be edited", Toast.LENGTH_SHORT).show(); return
        }
        open += OpenFile(f, f.readText()); active = open.lastIndex; showTree = false
    }
    fun closeAt(i: Int) {
        val o = open[i]
        if (o.dirty) { saveAtomic(o.file, o.value.text) }
        open.removeAt(i)
        active = if (open.isEmpty()) -1 else minOf(i, open.lastIndex)
        if (open.isEmpty()) showTree = true
    }

    var goTo by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(openAt) {
        openAt?.let { (path, line) ->
            val f = File(project.dir, path)
            if (f.exists()) { openFile(f); open.firstOrNull { it.file == f }?.goTo(line); goTo = line }
        }
    }

    // Autosave: debounce 700 ms after the last edit, written atomically off the main thread.
    val cur = open.getOrNull(active)
    LaunchedEffect(cur, cur?.value?.text) {
        val o = cur ?: return@LaunchedEffect
        if (!o.dirty) return@LaunchedEffect
        delay(700)
        val t = o.value.text
        withContext(Dispatchers.IO) { saveAtomic(o.file, t); repo.touch(project) }
        o.saved = t
    }

    BackHandlerCompat(enabled = !showTree && open.isNotEmpty()) { showTree = true }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            project.name,
            if (showTree || cur == null) project.displayPath else cur.file.relativeTo(project.dir).path,
            onBack = { saveAll(); onBack() },
        ) {
            IconBtn(I.folder, "Files", { saveAll(); showTree = !showTree })
            IconBtn(I.play, "Build and run", { saveAll(); onRun() }, solid = true)
            IconBtn(I.more, "More", { menu = true })
        }
        if (open.isNotEmpty() && !showTree) FileTabs(open, active, { active = it }, ::closeAt) else Divider()

        if (showTree || cur == null) {
            val nodes = mutableListOf<Node>().also { refresh; flatten(project.dir, 0, expanded.toList(), it) }
            LazyColumn(Modifier.weight(1f)) {
                items(nodes, key = { it.file.path }) { n ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .combinedClickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    if (n.file.isDirectory) { if (n.file.path in expanded) expanded.remove(n.file.path) else expanded.add(n.file.path) }
                                    else openFile(n.file)
                                },
                                onLongClick = { nodeMenu = n.file },
                            )
                            .padding(start = (16 + n.depth * 18).dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Ico(if (n.file.isDirectory) I.folder else I.file, tint = LocalPalette.current.secondary, size = 18.dp)
                        Spacer(Modifier.width(12.dp))
                        Txt(n.file.name, T.body, maxLines = 1, modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            CodeEditor(cur, fontSize, wrap, onSave = { saveAll() }, onFontSize = onFontSize, onWrap = onWrap, modifier = Modifier.weight(1f), goToLine = goTo)
        }
    }

    if (menu) Sheet({ menu = false }) {
        SheetItem("New file") { menu = false; prompt = Prompt.NewFile(project.dir.resolve("app/src/main")) }
        SheetItem("Configuration") { menu = false; saveAll(); onConfig() }
        SheetItem("Dependencies") { menu = false; saveAll(); onDeps() }
        SheetItem("Preview") { menu = false; saveAll(); onPreview() }
        SheetItem("Git") { menu = false; saveAll(); onGit() }
        SheetItem("Export ZIP") { menu = false; saveAll(); onExport() }
        SheetItem("Delete project", danger = true) { menu = false; confirmProject = true }
    }
    nodeMenu?.let { f ->
        Sheet({ nodeMenu = null }) {
            if (f.isDirectory) SheetItem("New file here") { nodeMenu = null; prompt = Prompt.NewFile(f) }
            SheetItem("Rename") { nodeMenu = null; prompt = Prompt.Rename(f) }
            SheetItem("Delete", danger = true) { nodeMenu = null; confirm = f }
        }
    }
    prompt?.let { pr ->
        var text by remember(pr) { mutableStateOf(if (pr is Prompt.Rename) pr.file.name else "") }
        val bad = text.isBlank() || text.contains('/') || text.contains('\\')
        Sheet({ prompt = null }) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Field(if (pr is Prompt.Rename) "New name" else "File name", text, { text = it }, mono = true)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Cancel", { prompt = null }, Modifier.weight(1f), solid = false)
                    Btn("OK", {
                        when (pr) {
                            is Prompt.NewFile -> { val f = File(pr.dir, text.trim()); f.parentFile?.mkdirs(); if (!f.exists()) f.createNewFile(); openFile(f) }
                            is Prompt.Rename -> {
                                val t = File(pr.file.parentFile, text.trim())
                                if (!t.exists() && pr.file.renameTo(t)) {
                                    val i = open.indexOfFirst { it.file == pr.file }
                                    if (i >= 0) closeAt(i)
                                }
                            }
                        }
                        refresh++; prompt = null
                    }, Modifier.weight(1f), enabled = !bad)
                }
            }
        }
    }
    confirm?.let { f ->
        Sheet({ confirm = null }) {
            Txt("Delete ${f.name}?", T.section, modifier = Modifier.padding(20.dp))
            Txt("This cannot be undone.", T.body, LocalPalette.current.secondary, Modifier.padding(horizontal = 20.dp))
            Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("Cancel", { confirm = null }, Modifier.weight(1f), solid = false)
                Btn("Delete", {
                    open.indexOfFirst { it.file == f }.takeIf { it >= 0 }?.let { open.removeAt(it); active = open.lastIndex }
                    f.deleteRecursively(); confirm = null; refresh++
                }, Modifier.weight(1f))
            }
        }
    }
    if (confirmProject) Sheet({ confirmProject = false }) {
        Txt("Delete ${project.name}?", T.section, modifier = Modifier.padding(20.dp))
        Txt("All project files will be removed from this device.", T.body, LocalPalette.current.secondary, Modifier.padding(horizontal = 20.dp))
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Cancel", { confirmProject = false }, Modifier.weight(1f), solid = false)
            Btn("Delete", { open.clear(); repo.delete(project); onDeleted() }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BackHandlerCompat(enabled: Boolean, onBack: () -> Unit) =
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)

private class ApkInfo(val label: String, val pkg: String, val version: String)

private fun readApk(ctx: Context, f: File): ApkInfo? {
    val pm = ctx.packageManager
    val info = pm.getPackageArchiveInfo(f.path, 0) ?: return null
    val ai = info.applicationInfo?.also { it.sourceDir = f.path; it.publicSourceDir = f.path }
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    return ApkInfo(ai?.loadLabel(pm)?.toString() ?: f.name, info.packageName, "${info.versionName ?: "?"} ($code)")
}

@Composable
fun ResultScreen(project: Project, variant: BuildVariant, onBack: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val apk = project.output(variant)
    val info = remember(apk) { if (apk.exists()) readApk(ctx, apk) else null }
    val scope = rememberCoroutineScope()
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(variant.mime)) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { o -> apk.inputStream().use { it.copyTo(o) } } }.isSuccess
            }
            Toast.makeText(ctx, if (ok) "Saved" else "Could not save the file", Toast.LENGTH_SHORT).show()
        }
    }
    var needPermission by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar(project.name, onBack = onBack)
        Column(Modifier.weight(1f).padding(20.dp)) {
            Spacer(Modifier.height(20.dp))
            Txt(if (apk.exists()) "Build successful" else "No file found", T.title.copy(fontSize = 32.sp))
            Spacer(Modifier.height(6.dp))
            Txt(if (variant == BuildVariant.DEBUG) "Debug APK" else variant.label, T.body, p.secondary)
            Spacer(Modifier.height(24.dp)); Divider()
            Row(Modifier.padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(apk, project.name, 52); Spacer(Modifier.width(14.dp))
                Column {
                    Txt(info?.label ?: project.name, T.section)
                    Txt(info?.pkg ?: project.pkg, T.code, p.secondary)
                    if (apk.exists()) Txt("Version ${info?.version ?: "?"} · ${formatSize(apk.length())}", T.secondary, p.secondary)
                }
            }
            Divider()
            Txt(variant.path, T.code, p.secondary, Modifier.padding(vertical = 14.dp))
            if (project.log.exists()) Column(Modifier.tap({ showLog = true }, "View build log")) {
                Txt("View build log", T.button); Spacer(Modifier.height(2.dp)); Box(Modifier.width(100.dp).height(2.dp).background(p.ink))
            }
        }
        if (apk.exists()) Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (variant != BuildVariant.RELEASE_AAB) Btn("Install", {
                    if (ctx.packageManager.canRequestPackageInstalls()) installApk(ctx, apk) else needPermission = true
                }, Modifier.weight(1f), icon = I.download, accent = true)
                Btn("Share", { shareFile(ctx, apk, variant.mime) }, Modifier.weight(1f), solid = variant == BuildVariant.RELEASE_AAB, icon = I.share)
            }
            Btn("Save to storage", { saver.launch("${project.dir.name}-${variant.suffix}") }, Modifier.fillMaxWidth(), solid = false, icon = I.upload)
        }
    }
    if (needPermission) Sheet({ needPermission = false }) {
        Txt("Allow installs from App Builder", T.section, modifier = Modifier.padding(20.dp))
        Txt("Android needs your permission to install apps from this source. You will be taken to the setting; nothing is changed automatically.", T.body, p.secondary, Modifier.padding(horizontal = 20.dp))
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Cancel", { needPermission = false }, Modifier.weight(1f), solid = false)
            Btn("Open settings", {
                needPermission = false
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
            }, Modifier.weight(1f))
        }
    }
    if (showLog) Sheet({ showLog = false }) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(20.dp)) {
            Txt(project.log.readText(), T.code, p.ink)
        }
    }
}

fun installApk(ctx: Context, f: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun shareFile(ctx: Context, f: File, mime: String) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
