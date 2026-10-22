package dev.appbuilder

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ---------------------------------------------------------------- value helpers

private class PTheme(val dark: Boolean) {
    val surface = if (dark) Color(0xFF1C1B1F) else Color(0xFFFFFBFE)
    val onSurface = if (dark) Color(0xFFE6E1E5) else Color(0xFF1C1B1F)
    val primary = if (dark) Color(0xFFD0BCFF) else Color(0xFF6650A4)
    val onPrimary = if (dark) Color(0xFF381E72) else Color.White
    val container = if (dark) Color(0xFF2B2930) else Color(0xFFF3EDF7)
    val outline = if (dark) Color(0xFF938F99) else Color(0xFF79747E)
    val error = if (dark) Color(0xFFF2B8B5) else Color(0xFFB3261E)
}

private fun PValue?.dp(): Dp? = (this as? PValue.Num)?.let { if (it.unit == "dp" || it.unit == "") it.n.toFloat().dp else null }
private fun PValue?.sp() = (this as? PValue.Num)?.n?.toFloat()?.sp
private fun PValue?.str(): String? = (this as? PValue.Str)?.s
private fun PValue?.leaf(): String = when (this) { is PValue.Ref -> name.substringAfterLast('.'); is PValue.PCall -> name.substringAfterLast('.'); else -> "" }

private fun PValue?.color(t: PTheme): Color? = when (this) {
    is PValue.ColorV -> Color(argb.toInt())
    is PValue.Ref -> when (name.substringAfterLast('.')) {
        "primary" -> t.primary; "onPrimary" -> t.onPrimary; "surface", "background" -> t.surface; "onSurface", "onBackground" -> t.onSurface
        "error" -> t.error; "outline" -> t.outline; "surfaceVariant", "primaryContainer", "secondaryContainer", "surfaceContainer" -> t.container
        "Red" -> Color.Red; "Green" -> Color.Green; "Blue" -> Color.Blue; "Black" -> Color.Black; "White" -> Color.White; "Gray" -> Color.Gray
        "LightGray" -> Color.LightGray; "DarkGray" -> Color.DarkGray; "Yellow" -> Color.Yellow; "Cyan" -> Color.Cyan; "Magenta" -> Color.Magenta
        "Transparent" -> Color.Transparent
        else -> null
    }
    else -> null
}

private fun weightOf(w: FontWeight?) = w
private fun fontWeight(v: PValue?): FontWeight? = when (v.leaf()) {
    "Bold" -> FontWeight.Bold; "SemiBold" -> FontWeight.SemiBold; "Medium" -> FontWeight.Medium; "Light" -> FontWeight.Light; "Normal" -> FontWeight.Normal; "Black", "ExtraBold" -> FontWeight.ExtraBold
    else -> null
}

private fun PNode.arg(name: String, index: Int = -1): PValue? = args[name] ?: positional.getOrNull(index)

private fun hAlign(v: PValue?): Alignment.Horizontal = when (v.leaf()) { "CenterHorizontally" -> Alignment.CenterHorizontally; "End" -> Alignment.End; else -> Alignment.Start }
private fun vAlign(v: PValue?): Alignment.Vertical = when (v.leaf()) { "CenterVertically" -> Alignment.CenterVertically; "Bottom" -> Alignment.Bottom; else -> Alignment.Top }
private fun boxAlign(v: PValue?): Alignment = when (v.leaf()) {
    "Center" -> Alignment.Center; "TopEnd" -> Alignment.TopEnd; "TopCenter" -> Alignment.TopCenter; "BottomStart" -> Alignment.BottomStart
    "BottomEnd" -> Alignment.BottomEnd; "BottomCenter" -> Alignment.BottomCenter; "CenterStart" -> Alignment.CenterStart; "CenterEnd" -> Alignment.CenterEnd
    else -> Alignment.TopStart
}
private fun vArr(v: PValue?): Arrangement.Vertical = when {
    v is PValue.PCall && v.name.endsWith("spacedBy") -> Arrangement.spacedBy(v.args.firstOrNull().dp() ?: 0.dp)
    v.leaf() == "Center" -> Arrangement.Center; v.leaf() == "Bottom" -> Arrangement.Bottom; v.leaf() == "SpaceBetween" -> Arrangement.SpaceBetween
    v.leaf() == "SpaceAround" -> Arrangement.SpaceAround; v.leaf() == "SpaceEvenly" -> Arrangement.SpaceEvenly
    else -> Arrangement.Top
}
private fun hArr(v: PValue?): Arrangement.Horizontal = when {
    v is PValue.PCall && v.name.endsWith("spacedBy") -> Arrangement.spacedBy(v.args.firstOrNull().dp() ?: 0.dp)
    v.leaf() == "Center" -> Arrangement.Center; v.leaf() == "End" -> Arrangement.End; v.leaf() == "SpaceBetween" -> Arrangement.SpaceBetween
    v.leaf() == "SpaceAround" -> Arrangement.SpaceAround; v.leaf() == "SpaceEvenly" -> Arrangement.SpaceEvenly
    else -> Arrangement.Start
}

private fun shapeOf(v: PValue?, fallback: Dp = 0.dp) = when {
    v is PValue.PCall && v.name.endsWith("RoundedCornerShape") -> RoundedCornerShape(v.args.firstOrNull().dp() ?: v.named["size"].dp() ?: fallback)
    v.leaf() == "CircleShape" -> CircleShape
    else -> RoundedCornerShape(fallback)
}

/** Turns the modifier chain into a Compose Modifier. Order is kept, like in real Compose. */
private fun modifierOf(mods: List<PMod>, t: PTheme, rowScope: RowScope?, colScope: ColumnScope?): Modifier {
    var m: Modifier = Modifier
    for (pm in mods) {
        val a = pm.positional; val n = pm.named
        m = when (pm.name) {
            "fillMaxWidth" -> m.fillMaxWidth()
            "fillMaxHeight" -> m.fillMaxHeight()
            "fillMaxSize" -> m.fillMaxSize()
            "size" -> m.size(a.firstOrNull().dp() ?: n["size"].dp() ?: 0.dp)
            "width" -> a.firstOrNull().dp()?.let { m.width(it) } ?: m
            "height" -> a.firstOrNull().dp()?.let { m.height(it) } ?: m
            "defaultMinSize" -> m
            "padding" -> {
                val all = a.firstOrNull().dp() ?: n["all"].dp()
                when {
                    all != null && a.size == 1 -> m.padding(all)
                    n.isNotEmpty() || a.isNotEmpty() -> m.padding(
                        start = n["start"].dp() ?: n["horizontal"].dp() ?: a.getOrNull(0).takeIf { n.isEmpty() }.dp() ?: 0.dp,
                        top = n["top"].dp() ?: n["vertical"].dp() ?: a.getOrNull(1).dp() ?: 0.dp,
                        end = n["end"].dp() ?: n["horizontal"].dp() ?: a.getOrNull(2).dp() ?: a.getOrNull(0).takeIf { n.isEmpty() }.dp() ?: 0.dp,
                        bottom = n["bottom"].dp() ?: n["vertical"].dp() ?: a.getOrNull(3).dp() ?: a.getOrNull(1).dp() ?: 0.dp,
                    )
                    else -> m
                }
            }
            "background" -> (a.firstOrNull() ?: n["color"]).color(t)?.let { c -> m.background(c, shapeOf(a.getOrNull(1) ?: n["shape"])) } ?: m
            "clip" -> m.clip(shapeOf(a.firstOrNull() ?: n["shape"]))
            "border" -> {
                val w = a.firstOrNull().dp() ?: n["width"].dp() ?: 1.dp
                val c = (a.getOrNull(1) ?: n["color"]).color(t) ?: t.outline
                m.border(w, c, shapeOf(a.getOrNull(2) ?: n["shape"]))
            }
            "weight" -> {
                val w = ((a.firstOrNull() ?: n["weight"]) as? PValue.Num)?.n?.toFloat() ?: 1f
                when { rowScope != null -> with(rowScope) { m.weight(w) }; colScope != null -> with(colScope) { m.weight(w) }; else -> m }
            }
            else -> m // clickable, testTag, semantics ... change nothing visible
        }
    }
    return m
}

// ---------------------------------------------------------------- renderer

/** The interpreter gives a tree of known composables; this draws an approximation with plain Compose foundation widgets. */
@Composable
private fun Render(nodes: List<PNode>, t: PTheme, rowScope: RowScope? = null, colScope: ColumnScope? = null) {
    nodes.forEach { Render(it, t, rowScope, colScope) }
}

@Composable
private fun Render(n: PNode, t: PTheme, rowScope: RowScope?, colScope: ColumnScope?) {
    val mod = modifierOf(n.modifier, t, rowScope, colScope)
    val label = n.arg("text", 0).str()
    when (n.name) {
        "Column", "LazyColumn", "FlowColumn" -> Column(mod, vArr(n.args["verticalArrangement"]), hAlign(n.args["horizontalAlignment"])) { Render(n.children, t, null, this) }
        "Row", "LazyRow", "FlowRow", "NavigationBar", "BottomAppBar", "TabRow" -> Row(mod, hArr(n.args["horizontalArrangement"]), vAlign(n.args["verticalAlignment"])) { Render(n.children, t, this, null) }
        "Box", "BoxWithConstraints", "ConstraintLayout" -> Box(mod, boxAlign(n.args["contentAlignment"])) { Render(n.children, t) }
        "Text" -> {
            val size = n.args["fontSize"].sp()
            androidx.compose.foundation.text.BasicText(
                label ?: "Text",
                mod,
                T.body.copy(
                    color = n.args["color"].color(t) ?: LocalPreviewInk.current.takeIf { it != Color.Unspecified } ?: t.onSurface,
                    fontSize = size ?: 14.sp,
                    fontWeight = fontWeight(n.args["fontWeight"]),
                ),
                maxLines = ((n.args["maxLines"] as? PValue.Num)?.n?.toInt()) ?: Int.MAX_VALUE,
            )
        }
        "Button", "FilledTonalButton", "ElevatedButton", "OutlinedButton", "TextButton" -> {
            val filled = n.name != "OutlinedButton" && n.name != "TextButton"
            Row(
                mod.defaultMinSize(minHeight = 40.dp).clip(RoundedCornerShape(20.dp))
                    .background(if (filled) (if (n.name == "Button") t.primary else t.container) else Color.Transparent)
                    .then(if (n.name == "OutlinedButton") Modifier.border(1.dp, t.outline, RoundedCornerShape(20.dp)) else Modifier)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) { CompositionLocalProvider(LocalPreviewInk provides (if (n.name == "Button") t.onPrimary else t.primary)) { Render(n.children, t, this, null) } }
        }
        "IconButton", "Icon" -> Box(mod.size(24.dp).clip(CircleShape).background(t.outline.copy(alpha = 0.35f)))
        "FloatingActionButton", "SmallFloatingActionButton", "LargeFloatingActionButton", "ExtendedFloatingActionButton" ->
            Box(mod.defaultMinSize(56.dp, 56.dp).clip(RoundedCornerShape(16.dp)).background(t.container).padding(12.dp), Alignment.Center) { Render(n.children, t) }
        "Card", "ElevatedCard", "OutlinedCard", "Surface" -> {
            val bg = (n.args["color"] ?: n.args["containerColor"]).color(t) ?: if (n.name == "Surface") t.surface else t.container
            Column(
                mod.clip(shapeOf(n.args["shape"], 12.dp)).background(bg).then(if (n.name == "OutlinedCard") Modifier.border(1.dp, t.outline, shapeOf(n.args["shape"], 12.dp)) else Modifier),
            ) { Render(n.children, t, null, this) }
        }
        "Scaffold" -> Column(mod.fillMaxSize().background(t.surface)) {
            Render(n.slots["topBar"].orEmpty(), t, null, this)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize()) { Render(n.children, t, null, this) }
                Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Render(n.slots["floatingActionButton"].orEmpty(), t) }
            }
            Render(n.slots["bottomBar"].orEmpty(), t, null, this)
        }
        "TopAppBar", "CenterAlignedTopAppBar", "LargeTopAppBar", "MediumTopAppBar" ->
            Row(mod.fillMaxWidth().background(t.surface).padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Render(n.slots["navigationIcon"].orEmpty(), t, this, null)
                Box(Modifier.weight(1f)) { Render(n.slots["title"].orEmpty(), t) }
                Render(n.slots["actions"].orEmpty(), t, this, null)
            }
        "NavigationBarItem", "Tab", "NavigationRail" ->
            Column(mod.padding(8.dp).then(if (rowScope != null) with(rowScope) { Modifier.weight(1f) } else Modifier), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(t.outline.copy(alpha = 0.35f)))
                Render(n.slots["label"].orEmpty() + n.children, t, null, this)
            }
        "Spacer" -> Spacer(mod)
        "Divider", "HorizontalDivider" -> Box(mod.fillMaxWidth().height(1.dp).background(t.outline.copy(alpha = 0.5f)))
        "VerticalDivider" -> Box(mod.fillMaxHeight().width(1.dp).background(t.outline.copy(alpha = 0.5f)))
        "Image", "AsyncImage" -> Box(mod.defaultMinSize(48.dp, 48.dp).background(t.container), Alignment.Center) { Txt("Image", T.code.copy(fontSize = 10.sp), t.outline) }
        "TextField", "OutlinedTextField" -> Box(mod.defaultMinSize(minHeight = 56.dp).border(1.dp, t.outline, RoundedCornerShape(4.dp)).padding(16.dp), Alignment.CenterStart) {
            val v = n.args["value"].str() ?: ""
            if (v.isNotEmpty()) androidx.compose.foundation.text.BasicText(v, style = T.body.copy(color = t.onSurface))
            else Render(n.slots["label"].orEmpty() + n.slots["placeholder"].orEmpty(), t)
        }
        "Switch" -> Box(mod.size(52.dp, 32.dp).clip(RoundedCornerShape(16.dp)).background(if ((n.args["checked"] as? PValue.Bool)?.b == true) t.primary else t.outline))
        "Checkbox", "RadioButton" -> Box(mod.size(20.dp).border(2.dp, if ((n.args["checked"] ?: n.args["selected"]).let { it is PValue.Bool && it.b }) t.primary else t.outline, if (n.name == "RadioButton") CircleShape else RoundedCornerShape(2.dp)))
        "Slider", "LinearProgressIndicator" -> Box(mod.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(t.container)) {
            val f = ((n.args["progress"] ?: n.positional.firstOrNull()) as? PValue.Num)?.n?.toFloat() ?: 0.4f
            Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).fillMaxHeight().background(t.primary))
        }
        "CircularProgressIndicator" -> Box(mod.size(40.dp).border(4.dp, t.primary, CircleShape))
        "ListItem" -> Column(mod.fillMaxWidth().padding(16.dp)) {
            Render(n.slots["overlineContent"].orEmpty() + n.slots["headlineContent"].orEmpty() + n.slots["supportingContent"].orEmpty(), t, null, this)
        }
        "FilterChip", "AssistChip", "SuggestionChip", "Badge" ->
            Row(mod.clip(RoundedCornerShape(8.dp)).border(1.dp, t.outline, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) { Render(n.slots["label"].orEmpty() + n.children, t, this, null) }
        else -> Box(mod.border(1.dp, t.error, RoundedCornerShape(4.dp)).padding(8.dp)) {
            Txt(n.name, T.code.copy(fontSize = 11.sp), t.error)
            Column { Render(n.children, t, null, this) }
        }
    }
}

private val LocalPreviewInk = compositionLocalOf { Color.Unspecified }

// ---------------------------------------------------------------- screen

private fun readProject(dir: File): Pair<Map<String, String>, Map<String, String>> {
    val src = File(dir, "app/src")
    val files = LinkedHashMap<String, String>()
    var strings = emptyMap<String, String>()
    src.walkTopDown().onEnter { it.name != "build" }.forEach { f ->
        if (!f.isFile || f.length() > 400_000) return@forEach
        val rel = f.relativeTo(dir).path
        if (f.name.endsWith(".kt")) files[rel] = f.readText()
        else if (f.name == "strings.xml" && f.path.contains("/values/")) strings = ComposePreviewParser.parseStrings(f.readText())
    }
    return files to strings
}

@Composable
fun PreviewScreen(project: Project, onBack: () -> Unit, onOpenFile: (String, Int) -> Unit) {
    val p = LocalPalette.current
    val defs by produceState<List<PreviewDef>?>(null, project) {
        value = withContext(Dispatchers.IO) {
            runCatching { val (f, s) = readProject(project.dir); ComposePreviewParser.findPreviews(f, s) }.getOrDefault(emptyList())
        }
    }
    var index by remember { mutableIntStateOf(0) }
    var dark by remember { mutableStateOf<Boolean?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var choose by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Compose preview", project.name, onBack = onBack, actions = { if ((defs?.size ?: 0) > 1) IconBtn(I.more, "Choose preview", { choose = true }) })
        val list = defs
        when {
            list == null -> Txt("Reading project...", T.body, p.secondary, Modifier.padding(20.dp))
            list.isEmpty() -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt("No @Preview functions found", T.section)
                Txt("Add a @Preview to a @Composable function to see it here.", T.body, p.secondary)
            }
            else -> {
                val d = list[index.coerceIn(0, list.lastIndex)]
                val isDark = dark ?: d.night
                val t = remember(isDark) { PTheme(isDark) }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Txt(d.title, T.section, maxLines = 2)
                    Txt("${d.file.substringAfterLast('/')}:${d.line}", T.code.copy(fontSize = 11.sp), p.secondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip("Light", !isDark) { dark = false }
                        FilterChip("Dark", isDark) { dark = true }
                        FilterChip("Zoom", zoom != 1f) { zoom = if (zoom >= 2f) 0.75f else zoom + 0.25f }
                    }
                    val w = (d.widthDp ?: 360).coerceIn(120, 900)
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        val frame = Modifier.width((w * zoom).dp).then(if (d.heightDp != null) Modifier.height((d.heightDp * zoom).dp) else Modifier.heightIn(min = 120.dp))
                        Box(frame.clip(RoundedCornerShape(12.dp)).border(1.dp, p.border, RoundedCornerShape(12.dp)).background(d.backgroundColor?.let { Color(it.toInt()) } ?: t.surface)) {
                            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density * zoom, androidx.compose.ui.platform.LocalDensity.current.fontScale)) {
                                Box(Modifier.width(w.dp)) { Column { Render(d.root, t, null, this) } }
                            }
                        }
                    }
                    Txt("Preview is approximate: it shows layout and text, not exact Material styling.", T.secondary, p.secondary)
                    if (d.warnings.isNotEmpty()) {
                        Txt("Warnings", T.secondary, p.secondary)
                        d.warnings.distinct().forEach { Txt(it, T.secondary, p.secondary) }
                    }
                    Btn("Open file", { onOpenFile(d.file, d.line) }, Modifier.fillMaxWidth(), solid = false)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
    if (choose) Sheet({ choose = false }) {
        Txt("Choose preview", T.section, modifier = Modifier.padding(20.dp))
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            defs.orEmpty().forEachIndexed { i, d -> SheetItem(d.title) { index = i; choose = false } }
        }
    }
}
