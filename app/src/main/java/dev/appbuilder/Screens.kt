package dev.appbuilder

import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val TileColors = listOf(Color(0xFF2F5BD3), Color(0xFF1F2328), Color(0xFF127A8F), Color(0xFF4A5568))

@Composable
fun ProjectTile(name: String, size: Int = 44) {
    val c = TileColors[(name.hashCode() and 0x7fffffff) % TileColors.size]
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(c), contentAlignment = Alignment.Center) {
        Txt(name.first().uppercase(), T.section.copy(fontSize = (size * 0.42f).sp), Color.White)
    }
}

// ---------------------------------------------------------------- Projects

@Composable
fun ProjectsScreen(projects: List<Project>, onOpen: (Project) -> Unit, onNew: () -> Unit, onImport: () -> Unit, onClone: () -> Unit = {}) {
    val p = LocalPalette.current
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    val shown = projects.filter { pr ->
        (query.isBlank() || pr.name.contains(query, true) || pr.pkg.contains(query, true)) &&
            when (filter) {
                "Kotlin" -> pr.language == "Kotlin"
                "Java" -> pr.language == "Java"
                "Failed" -> pr.buildFailed
                else -> true
            }
    }
    val onAccent = Color(0xFF15171A)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (searching) {
                    Box(
                        Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(p.card)
                            .border(1.dp, p.border, RoundedCornerShape(24.dp)).padding(horizontal = 18.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            textStyle = T.body.copy(color = p.ink),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(p.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (query.isEmpty()) Txt("Search projects", T.body, p.secondary)
                    }
                    Spacer(Modifier.width(8.dp))
                    RoundBtn(I.close, "Close search") { searching = false; query = "" }
                } else {
                    Column(Modifier.weight(1f)) {
                        Txt("Projects", T.title.copy(fontSize = 32.sp))
                        Spacer(Modifier.height(4.dp))
                        Txt("${projects.size} projects on this device", T.secondary, p.secondary)
                    }
                    RoundBtn(I.upload, "Import ZIP", onImport)
                    Spacer(Modifier.width(8.dp))
                    RoundBtn(I.search, "Search") { searching = true }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Kotlin", "Java", "Failed").forEach { f -> FilterChip(f, filter == f) { filter = f } }
            }
            if (shown.isEmpty()) {
                Txt(if (projects.isEmpty()) "No projects yet. Create one or import a ZIP." else "No matching projects.", T.body, p.secondary, Modifier.padding(20.dp))
            } else LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 180.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shown, key = { it.dir.path }) { pr ->
                    Row(Modifier.fillMaxWidth().card().tap({ onOpen(pr) }, "Open ${pr.name}").padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(pr.apk, pr.name, 52)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(pr.name, T.section.copy(fontWeight = FontWeight.Bold), maxLines = 1)
                            if (pr.pkg.isNotEmpty()) Txt(pr.pkg, T.code, p.secondary, maxLines = 1)
                            Txt("${pr.language} · ${pr.ui} · ${timeAgo(pr.modified)}", T.secondary, p.secondary, maxLines = 1)
                        }
                        if (pr.buildFailed) Pill("Failed", false) else if (pr.apk.exists()) Pill("Success", true)
                    }
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 172.dp).height(48.dp)
                .clip(RoundedCornerShape(24.dp)).background(p.card).border(1.dp, p.border, RoundedCornerShape(24.dp)).tap(onClone, "Clone repository").padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ico(I.download, size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Txt("Clone repository", T.button.copy(fontSize = 14.sp))
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 104.dp).height(56.dp)
                .clip(RoundedCornerShape(28.dp)).background(p.accent).tap(onNew, "New project").padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ico(I.plus, tint = onAccent, size = 22.dp)
            Spacer(Modifier.width(10.dp))
            Txt("New project", T.button.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp), onAccent)
        }
    }
}

// ---------------------------------------------------------------- New project

@Composable
fun NewProjectScreen(repo: ProjectRepo, onBack: () -> Unit, onCreated: (Project) -> Unit) {
    var name by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf("com.example.") }
    var lang by remember { mutableStateOf("Kotlin") }
    var ui by remember { mutableStateOf("Compose") }
    var tried by remember { mutableStateOf(false) }
    val nameErr = if (tried) repo.validateName(name.trim()) else null
    val pkgErr = if (tried) repo.validatePackage(pkg) else null
    Column(Modifier.fillMaxSize()) {
        TopBar("New project", onBack = onBack)
        Divider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Field("Project name", name, { name = it }, error = nameErr)
            Field("Package name", pkg, { pkg = it.lowercase() }, mono = true, error = pkgErr, keyboard = KeyboardType.Uri)
            Column { Txt("Language", T.secondary, LocalPalette.current.secondary); Spacer(Modifier.height(6.dp))
                Segmented(listOf("Kotlin", "Java"), lang, { lang = it; if (it == "Java") ui = "XML" }) }
            Column { Txt("UI", T.secondary, LocalPalette.current.secondary); Spacer(Modifier.height(6.dp))
                Segmented(listOf("Compose", "XML"), ui, { ui = it }, enabled = if (lang == "Java") listOf("XML") else listOf("Compose", "XML"))
                if (lang == "Java") { Spacer(Modifier.height(6.dp)); Txt("Jetpack Compose requires Kotlin.", T.secondary, LocalPalette.current.secondary) } }
            Column { Row2("Min SDK", "26"); Row2("Target / Compile SDK", "36"); Row2("Location", "/Projects", mono = true) }
        }
        Box(Modifier.padding(16.dp)) {
            Btn("Create project", {
                tried = true
                if (repo.validateName(name.trim()) == null && repo.validatePackage(pkg) == null) onCreated(repo.create(name.trim(), pkg, lang, ui))
            }, Modifier.fillMaxWidth())
        }
    }
}

// ---------------------------------------------------------------- Import

@Composable
fun ImportScreen(a: Analysis, repo: ProjectRepo, env: BuildEnvironment, onBack: () -> Unit, onChoose: () -> Unit, onImported: (Project) -> Unit) {
    val p = LocalPalette.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        TopBar("Import project", onBack = onBack)
        when (a) {
            is Valid -> {
                var name by remember { mutableStateOf(a.suggestedName) }
                val err = repo.validateName(name.trim())
                // A Compile SDK newer than anything this device can get would stop the build later; say so now.
                val newest by produceState<Int?>(null) { value = withContext(Dispatchers.IO) { runCatching { Preflight.maxKnownPlatform(env) }.getOrNull() } }
                val wantedApi = a.compileSdk.toIntOrNull()
                val tooNew = wantedApi != null && newest != null && wantedApi > newest!!
                var lower by remember { mutableStateOf(true) }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Column(Modifier.padding(20.dp)) {
                        Txt("Android project detected", T.title.copy(fontSize = 26.sp))
                        Spacer(Modifier.height(6.dp))
                        Txt("${a.fileName} · ${formatSize(a.size)}", T.code, p.secondary)
                    }
                    Divider()
                    Row2("Build", "Gradle"); Row2("Language", a.language); Row2("UI", a.ui); Row2("Compile SDK", a.compileSdk)
                    if (tooNew) Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Txt("Compile SDK $wantedApi is not available in this app's SDK list (newest: API $newest). The build would stop.", T.body, p.secondary)
                        Btn(if (lower) "Lower Compile SDK to $newest on import" else "Keep Compile SDK $wantedApi", { lower = !lower }, Modifier.fillMaxWidth(), solid = false)
                    }
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Field("Name", name, { name = it }, error = err)
                        Field("Location", "/Projects/${name.trim()}", {}, mono = true, readOnly = true)
                    }
                }
                Box(Modifier.padding(16.dp)) {
                    Btn("Import project", {
                        if (err == null) {
                            val proj = repo.importZip(a, name)
                            Toolchain.ensureWrapperProps(proj.dir, LearnedStore.map())?.let { v ->
                                Toast.makeText(ctx, tr("Created gradle-wrapper.properties for Gradle $v"), Toast.LENGTH_LONG).show()
                            }
                            if (tooNew && lower) runCatching { GradleEdit.write(proj.dir, mapOf("compileSdk" to newest.toString())) }
                            onImported(proj)
                        }
                    }, Modifier.fillMaxWidth(), enabled = err == null, icon = I.download)
                }
            }
            is Invalid -> {
                Column(Modifier.weight(1f)) {
                    Column(Modifier.padding(20.dp)) {
                        Txt("Project cannot be imported", T.title.copy(fontSize = 26.sp))
                        Spacer(Modifier.height(6.dp))
                        Txt("${a.fileName} · ${formatSize(a.size)}", T.code, p.secondary)
                    }
                    Divider()
                    Txt("Missing", T.secondary, p.secondary, Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
                    a.missing.forEach { m ->
                        Txt(m, T.code.copy(fontSize = 14.sp), modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                        Divider(Modifier.padding(horizontal = 20.dp))
                    }
                    Txt("Choose a ZIP that contains a Gradle-based Android project.", T.body, p.secondary, Modifier.padding(20.dp))
                }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Choose another ZIP", onChoose, Modifier.fillMaxWidth())
                    Btn("Cancel", onBack, Modifier.fillMaxWidth(), solid = false)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Builds / SDK / Settings tabs

@Composable
private fun SettingCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().card().padding(16.dp)) {
        Txt(title, T.secondary, p.secondary)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
fun SettingsScreen(
    prefs: Prefs, env: BuildEnvironment, theme: String, onTheme: (String) -> Unit, fontSize: Int, onFont: (Int) -> Unit,
    language: String, onLanguage: (String) -> Unit, onAbout: () -> Unit, onGitHub: () -> Unit = {},
) {
    val p = LocalPalette.current
    var signingOpen by remember { mutableStateOf(false) }
    val keyAlias = remember(signingOpen) { prefs.signing()?.alias }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp)) { Txt("Settings", T.title.copy(fontSize = 32.sp)) }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingCard("Language") { Segmented(listOf("System", "English", "Indonesia"), language, onLanguage) }
            SettingCard("GitHub") { Btn(if (GitHubSession(androidx.compose.ui.platform.LocalContext.current).signedIn) "Signed in" else "Sign in to GitHub", onGitHub, Modifier.fillMaxWidth(), solid = false) }
            SettingCard("Appearance") { Segmented(listOf("Light", "Dark", "System"), theme, onTheme) }
            SettingCard("Editor font size") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Btn("-", { onFont(fontSize - 1) }, Modifier.width(56.dp), solid = false)
                    Txt("$fontSize", T.section, modifier = Modifier.width(72.dp).wrapContentWidth(Alignment.CenterHorizontally))
                    Btn("+", { onFont(fontSize + 1) }, Modifier.width(56.dp), solid = false)
                }
            }
            Row(Modifier.fillMaxWidth().card().tap({ signingOpen = true }, "Signing key").padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt("Signing key", T.secondary, p.secondary)
                    Spacer(Modifier.height(4.dp))
                    Txt(keyAlias ?: "Not set", T.body)
                }
                Ico(I.chevron, tint = p.secondary, size = 18.dp)
            }
            Row(Modifier.fillMaxWidth().card().tap(onAbout, "About").padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("About", T.body, modifier = Modifier.weight(1f))
                Ico(I.chevron, tint = p.secondary, size = 18.dp)
            }
        }
        Spacer(Modifier.height(120.dp))
    }
    if (signingOpen) SigningManager(env, prefs) { signingOpen = false }
}

/** Floating pill: solid, no shadow or blur. The active tab expands into an accent pill with its label. */
@Composable
fun FloatingNav(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val items = listOf("Projects" to I.folder, "Builds" to I.bolt, "SDK" to I.cube, "Settings" to I.sliders)
    val bar = if (p.dark) Color(0xFF2B3035) else Color(0xFF15171A)
    val onAccent = Color(0xFF15171A)
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 20.dp).height(68.dp)
            .clip(RoundedCornerShape(34.dp)).background(bar).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, (label, icon) ->
            if (i == selected) {
                Row(
                    Modifier.height(52.dp).clip(RoundedCornerShape(26.dp)).background(p.accent).tap({ onSelect(i) }, label).padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Ico(icon, tint = onAccent, size = 22.dp)
                    Spacer(Modifier.width(8.dp))
                    Txt(label, T.button.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp), onAccent)
                }
            } else {
                Box(Modifier.width(56.dp).height(52.dp).tap({ onSelect(i) }, label), contentAlignment = Alignment.Center) {
                    Ico(icon, tint = Color(0xFFC9CCD0), size = 22.dp)
                }
            }
        }
    }
}
