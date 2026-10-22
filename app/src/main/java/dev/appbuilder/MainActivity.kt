package dev.appbuilder

import android.app.Activity
import android.os.Bundle
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface Route {
    data object Main : Route
    data object New : Route
    data object About : Route
    class Import(val a: Analysis) : Route
    class Proj(val p: Project, val openAt: Pair<String, Int>? = null) : Route
    class Build(val p: Project, val start: Boolean, val ready: Boolean = false) : Route
    class Log(val item: HistoryItem) : Route
    class Result(val p: Project, val variant: BuildVariant) : Route
    class Config(val p: Project) : Route
    class Deps(val p: Project) : Route
    class Git(val p: Project) : Route
    class Preview(val p: Project) : Route
    class GitHub(val p: Project?) : Route
    data object Clone : Route
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        L.apply(Prefs(this).language)
        LearnedStore.file = java.io.File(filesDir, "learned-toolchain.txt")
        GitEnv.configure(filesDir)
        setContent { Root() }
    }
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { ProjectRepo(ctx) }
    val env = remember { BuildEnvironment(ctx) }
    val prefs = remember { Prefs(ctx) }

    var theme by remember { mutableStateOf(prefs.theme) }
    var fontSize by remember { mutableIntStateOf(prefs.fontSize) }
    var language by remember { mutableStateOf(prefs.language) }
    var wrap by remember { mutableStateOf(prefs.wordWrap) }
    var projects by remember { mutableStateOf(repo.list()) }
    var route by remember { mutableStateOf<Route>(Route.Main) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var pendingComponent by remember { mutableStateOf<EnvComponent?>(null) }
    val build by BuildManager.state.collectAsState()

    // Crash recovery: a RUNNING marker with no live build means the app died mid-build.
    var unfinished by remember {
        mutableStateOf(if (BuildManager.state.value.running) null else projects.firstOrNull { File(it.dir, RUNNING).exists() })
    }

    val dark = when (theme) { "Dark" -> true; "Light" -> false; else -> isSystemInDarkTheme() }
    fun reload() { projects = repo.list() }

    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val a = withContext(Dispatchers.IO) { repo.analyze(uri) }
            route = Route.Import(a)
        }
    }
    val chooseZip = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }

    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val c = pendingComponent
        if (uri != null && c != null) scope.launch {
            withContext(Dispatchers.IO) { env.runSafely(c.id) { env.importArchive(c, uri) } }
            reload()
        }
        pendingComponent = null
    }

    val libPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { withContext(Dispatchers.IO) { runCatching { env.importLibrary(uri) } } }
    }

    AppTheme(dark) {
        val p = LocalPalette.current
        val view = LocalView.current
        val inProject = route is Route.Proj
        // The editor is always dark, so the system bar icons must follow the screen, not the app theme.
        SideEffect {
            (view.context as? Activity)?.window?.let { w ->
                val c = WindowCompat.getInsetsController(w, view)
                c.isAppearanceLightStatusBars = !(dark || inProject)
                c.isAppearanceLightNavigationBars = !(dark || inProject)
            }
        }
        Column(Modifier.fillMaxSize().background(if (inProject) DarkPalette.bg else p.bg).systemBarsPadding().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                BackHandler(enabled = route !is Route.Main) {
                    route = when (val r = route) {
                        is Route.Build -> Route.Proj(r.p)
                        is Route.Config -> Route.Proj(r.p)
                        is Route.Git -> Route.Proj(r.p)
                        is Route.Preview -> Route.Proj(r.p)
                        is Route.GitHub -> r.p?.let { Route.Git(it) } ?: Route.Main
                        is Route.Deps -> Route.Proj(r.p)
                        else -> Route.Main
                    }
                    reload()
                }
                when (val r = route) {
                    Route.Main -> when (tab) {
                        0 -> ProjectsScreen(projects, { route = Route.Proj(it) }, { route = Route.New }, chooseZip, { route = Route.Clone })
                        1 -> BuildsScreen(projects) { item -> route = Route.Log(item) }
                        2 -> SdkScreen(env, { c -> pendingComponent = c; archivePicker.launch(arrayOf("*/*")) }, { libPicker.launch(arrayOf("*/*")) })
                        else -> SettingsScreen(prefs, env, theme, { theme = it; prefs.theme = it }, fontSize, { fontSize = it.coerceIn(9, 28); prefs.fontSize = fontSize }, language, { language = it; prefs.language = it; L.apply(it) }, { route = Route.About }, { route = Route.GitHub(null) })
                    }
                    Route.New -> NewProjectScreen(repo, { route = Route.Main }, { reload(); route = Route.Proj(it) })
                    is Route.Import -> ImportScreen(r.a, repo, env, { route = Route.Main }, chooseZip, { reload(); route = Route.Proj(it) })
                    is Route.Proj -> CompositionLocalProvider(LocalPalette provides DarkPalette) { ProjectScreen(
                        r.p, repo, fontSize, wrap, { wrap = !wrap; prefs.wordWrap = wrap }, { fontSize = it; prefs.fontSize = it }, r.openAt,
                        onBack = { reload(); route = Route.Main },
                        onRun = { route = Route.Build(r.p, false, true) },
                        onExport = { scope.launch { shareFile(ctx, withContext(Dispatchers.IO) { repo.export(r.p) }, "application/zip") } },
                        onDeleted = { reload(); route = Route.Main },
                        onConfig = { route = Route.Config(r.p) },
                        onDeps = { route = Route.Deps(r.p) },
                        onGit = { route = Route.Git(r.p) },
                        onPreview = { route = Route.Preview(r.p) },
                    ) }
                    is Route.Build -> BuildScreen(
                        r.p, env, r.start, r.ready,
                        onBack = { reload(); route = Route.Proj(r.p) },
                        onOpenSdk = { tab = 2; route = Route.Main },
                        onOpenConfig = { route = Route.Config(r.p) },
                        onOpenDeps = { route = Route.Deps(r.p) },
                        onResult = { v -> reload(); route = Route.Result(r.p, v) },
                        onError = { f, l -> route = Route.Proj(r.p, f to l) },
                    )
                    is Route.Result -> ResultScreen(r.p, r.variant) { route = Route.Main }
                    is Route.Config -> ConfigScreen(r.p, repo) { reload(); route = Route.Proj(r.p) }
                    is Route.Preview -> PreviewScreen(r.p, { route = Route.Proj(r.p) }) { f, l -> route = Route.Proj(r.p, f to l) }
                    is Route.Git -> GitScreen(r.p, { reload(); route = Route.Proj(r.p) }, { route = Route.GitHub(r.p) })
                    is Route.GitHub -> GitHubScreen(repo, r.p, { route = r.p?.let { Route.Git(it) } ?: Route.Main }) { reload(); route = Route.Proj(it) }
                    Route.Clone -> CloneScreen(repo, "", { route = Route.Main }) { reload(); route = Route.Proj(it) }
                    is Route.Deps -> DepsScreen(r.p, repo) { reload(); route = Route.Proj(r.p) }
                    is Route.Log -> LogScreen(
                        r.item,
                        onBack = { route = Route.Main },
                        onResult = { route = Route.Result(r.item.project, BuildVariant.fromLabel(r.item.variant)) },
                        onError = { f, l -> route = Route.Proj(r.item.project, f to l) },
                    )
                    Route.About -> AboutScreen(env) { route = Route.Main }
                }
                if (route is Route.Main) FloatingNav(tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
            }
        }

        unfinished?.let { pr ->
            if (!build.running) Sheet({ unfinished = null }) {
                Txt("An unfinished build was detected.", T.section, modifier = Modifier.padding(20.dp))
                Txt(pr.name, T.body, p.secondary, Modifier.padding(horizontal = 20.dp))
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Dismiss", { File(pr.dir, RUNNING).delete(); unfinished = null }, Modifier.weight(1f), solid = false)
                    Btn("View build", { File(pr.dir, RUNNING).delete(); unfinished = null; route = Route.Build(pr, false) }, Modifier.weight(1f))
                }
            }
        }
    }
}
