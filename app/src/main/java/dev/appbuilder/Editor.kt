package dev.appbuilder

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class OpenFile(val file: File, text: String) {
    var value by mutableStateOf(TextFieldValue(text))
    var saved by mutableStateOf(text)
    val dirty get() = value.text != saved
    val undo = UndoStack()
    private fun TextFieldValue.state() = EditState(text, selection.start, selection.end)
    private fun EditState.value() = TextFieldValue(text, TextRange(selStart, selEnd))

    /** Every typed change goes through here so it can be undone. Newline and Tab keep the indentation. */
    fun edit(new: TextFieldValue, ext: String) {
        val old = value
        val result = smartEdit2(old, new, ext)
        if (result.text != old.text) undo.record(old.state(), result.state())
        value = result
    }
    fun replaceWith(text: String, selStart: Int = value.selection.start, selEnd: Int = selStart) {
        val old = value
        val after = EditState(text, selStart.coerceIn(0, text.length), selEnd.coerceIn(0, text.length))
        if (text != old.text) undo.record(old.state(), after)
        value = after.value()
    }
    fun doUndo() { undo.undo(value.state())?.let { value = it.value() } }
    fun doRedo() { undo.redo(value.state())?.let { value = it.value() } }
    fun indent(outdent: Boolean) {
        val v = value
        val r = AutoIndent.indentSelection(v.text, v.selection.start, v.selection.end, AutoIndent.detectUnit(v.text), outdent)
        replaceWith(r.text, r.selStart, r.selEnd)
    }
    fun select(a: Int, b: Int) { value = value.copy(selection = TextRange(a.coerceIn(0, value.text.length), b.coerceIn(0, value.text.length))) }

    fun goTo(line: Int) {
        val t = value.text
        var off = 0; var n = 1
        while (n < line) { val i = t.indexOf('\n', off); if (i < 0) break; off = i + 1; n++ }
        value = value.copy(selection = TextRange(off))
    }
}

/** Newline keeps the indentation (extra after an opening bracket); a typed closing bracket steps back one unit; the rest is [smartEdit]. */
fun smartEdit2(old: TextFieldValue, new: TextFieldValue, ext: String): TextFieldValue {
    if (old.selection.collapsed && new.selection.collapsed && new.text.length == old.text.length + 1) {
        val i = new.selection.start - 1
        val c = new.text.getOrNull(i)
        val unit = AutoIndent.detectUnit(old.text)
        if (c == '\n') {
            val r = AutoIndent.onNewline(new.text, new.selection.start, ext, unit)
            return TextFieldValue(r.text, TextRange(r.selStart, r.selEnd))
        }
        if (c != null && c in "})]" && old.text.getOrNull(i) != c) {
            AutoIndent.onCloseBracket(new.text, new.selection.start, c, unit)?.let { return TextFieldValue(it.text, TextRange(it.selStart, it.selEnd)) }
        }
    }
    return smartEdit(old, new)
}

/** Atomic write: temp file + rename, so a crash can never leave a half-written source file. */
fun saveAtomic(f: File, text: String) {
    val tmp = File(f.parentFile, f.name + ".tmp")
    tmp.writeText(text)
    Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
}

@Composable
fun FileTabs(files: List<OpenFile>, active: Int, onSelect: (Int) -> Unit, onClose: (Int) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        files.forEachIndexed { i, f ->
            val on = i == active
            Row(
                Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(if (on) p.ink else p.surface)
                    .tap({ onSelect(i) }, f.file.name).padding(start = 14.dp, end = if (on) 4.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(f.file.name + if (f.dirty) " *" else "", T.button.copy(fontSize = 13.sp), if (on) p.onInk else p.secondary, maxLines = 1)
                if (on) Box(Modifier.size(28.dp).tap({ onClose(i) }, "Close ${f.file.name}"), contentAlignment = Alignment.Center) {
                    Ico(I.close, tint = p.onInk, size = 14.dp)
                }
            }
        }
    }
}

@Composable
fun CodeEditor(
    of: OpenFile, fontSize: Int, wrap: Boolean, onSave: () -> Unit, onFontSize: (Int) -> Unit, onWrap: () -> Unit,
    modifier: Modifier = Modifier, goToLine: Int? = null,
) {
    val p = LocalPalette.current
    val ext = of.file.name.substringAfterLast('.', "")
    var zoom by remember(fontSize) { mutableFloatStateOf(fontSize.toFloat()) }
    val fs = zoom.roundToInt()
    val lineH = (fs * 1.6f).sp
    val style = T.code.copy(
        fontSize = fs.sp, lineHeight = lineH, color = p.ink,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    val highlighter = remember(ext, p) { Highlighter(ext, p) }
    val text = of.value.text
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val fresh = layout?.takeIf { it.layoutInput.text.text.length == text.length }

    // Line numbers come from the real layout, so they stay aligned when long lines wrap.
    val gutter = remember(fresh, text) {
        val l = fresh
        if (l == null) (1..(text.count { it == '\n' } + 1)).joinToString("\n")
        else {
            val sb = StringBuilder(); var n = 0
            for (v in 0 until l.lineCount) {
                if (v > 0) sb.append('\n')
                val st = l.getLineStart(v)
                if (st == 0 || text[st - 1] == '\n') { n++; sb.append(n) }
            }
            sb.toString()
        }
    }
    val sel = of.value.selection.start.coerceIn(0, text.length)
    val curRow = fresh?.getLineForOffset(sel) ?: text.take(sel).count { it == '\n' }

    val scroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val lineDp = with(density) { lineH.toDp() }
    val reportSize by rememberUpdatedState(onFontSize)
    val scope = rememberCoroutineScope()
    LaunchedEffect(goToLine, of) {
        if (goToLine != null) scroll.animateScrollTo(with(density) { ((goToLine - 3).coerceAtLeast(0) * lineH.toPx()).toInt() })
    }

    var findOpen by remember(of) { mutableStateOf(false) }
    var goOpen by remember(of) { mutableStateOf(false) }
    fun scrollToOffset(off: Int) {
        val l = fresh ?: return
        val y = l.getLineTop(l.getLineForOffset(off.coerceIn(0, text.length)))
        scope.launch { scroll.animateScrollTo((y - 120f).toInt().coerceAtLeast(0)) }
    }
    Column(modifier) {
        if (findOpen) FindBar(of, onClose = { findOpen = false }, onReveal = ::scrollToOffset)
        Box(
            Modifier.weight(1f).fillMaxWidth()
                // Two fingers zoom the text; one finger still scrolls and edits normally.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var zoomed = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.size >= 2) {
                                val z = event.calculateZoom()
                                if (z != 1f) { zoom = (zoom * z).coerceIn(9f, 28f); zoomed = true }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (zoomed) reportSize(zoom.roundToInt())
                    }
                }
                .verticalScroll(scroll)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) false
                    else when {
                        e.isCtrlPressed && e.key == Key.S -> { onSave(); true }
                        e.isCtrlPressed && e.key == Key.Z && !e.isShiftPressed -> { of.doUndo(); true }
                        (e.isCtrlPressed && e.key == Key.Y) || (e.isCtrlPressed && e.isShiftPressed && e.key == Key.Z) -> { of.doRedo(); true }
                        e.isCtrlPressed && e.key == Key.F -> { findOpen = true; true }
                        e.isCtrlPressed && e.key == Key.G -> { goOpen = true; true }
                        e.key == Key.Tab -> { of.indent(e.isShiftPressed); true }
                        else -> false
                    }
                },
        ) {
            // current line marker: tonal row + accent bar (8.dp = the padding of the text row below)
            Box(Modifier.offset(y = lineDp * curRow + 8.dp).fillMaxWidth().height(lineDp).background(p.surface))
            Box(Modifier.offset(y = lineDp * curRow + 8.dp).width(3.dp).height(lineDp).background(p.accent))
            Row(Modifier.padding(vertical = 8.dp)) {
                Txt(gutter, style.copy(textAlign = TextAlign.End), p.comment, Modifier.width(44.dp).padding(end = 12.dp))
                Box(if (wrap) Modifier.weight(1f) else Modifier.weight(1f).horizontalScroll(hScroll)) {
                    BasicTextField(
                        value = of.value,
                        onValueChange = { of.edit(it, ext) },
                        textStyle = style,
                        cursorBrush = SolidColor(p.accent),
                        visualTransformation = highlighter,
                        onTextLayout = { layout = it },
                        modifier = (
                            if (wrap) Modifier.fillMaxWidth().defaultMinSize(minHeight = 300.dp).padding(end = 12.dp)
                            else Modifier.defaultMinSize(minWidth = 300.dp, minHeight = 300.dp).padding(end = 48.dp)
                            ).focusRequester(focus),
                    )
                }
            }
        }
        if (goOpen) GoToDialog(of, { goOpen = false }, ::scrollToOffset)
        EditingToolbar(
            wrap = wrap, onWrap = onWrap,
            onUndo = { of.doUndo() }, onRedo = { of.doRedo() }, onFind = { findOpen = !findOpen }, onGoTo = { goOpen = true },
            onOutdent = { of.indent(true) },
            onSmaller = { zoom = (zoom - 1f).coerceAtLeast(9f); reportSize(zoom.roundToInt()) },
            onBigger = { zoom = (zoom + 1f).coerceAtMost(28f); reportSize(zoom.roundToInt()) },
        ) { s, back ->
            val v = of.value
            val t = v.text
            val a = v.selection.min; val b = v.selection.max
            if (s == "    ") of.indent(false)
            else of.replaceWith(t.substring(0, a) + s + t.substring(b), a + s.length - back)
        }
    }
}

@Composable
private fun ToolKey(label: String, on: Boolean, width: Int, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.size(width = width.dp, height = 44.dp).clip(shape).background(if (on) p.accent else p.surface).tap(onClick, label),
        contentAlignment = Alignment.Center,
    ) { Txt(label, T.code.copy(fontSize = 14.sp), if (on) Color(0xFF15171A) else p.ink) }
}

@Composable
private fun EditingToolbar(wrap: Boolean, onWrap: () -> Unit, onUndo: () -> Unit, onRedo: () -> Unit, onFind: () -> Unit, onGoTo: () -> Unit, onOutdent: () -> Unit, onSmaller: () -> Unit, onBigger: () -> Unit, insert: (String, Int) -> Unit) {
    val p = LocalPalette.current
    val keys = listOf("{ }" to "{}", "( )" to "()", "[ ]" to "[]", "\" \"" to "\"\"", "=" to "=", ":" to ":", ";" to ";", "->" to "->", "Tab" to "    ")
    Row(
        Modifier.fillMaxWidth().background(p.card).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToolKey("Undo", false, 64, onUndo)
        ToolKey("Redo", false, 64, onRedo)
        ToolKey("Find", false, 56, onFind)
        ToolKey("Go to line", false, 96, onGoTo)
        ToolKey("Outdent", false, 80, onOutdent)
        ToolKey("Wrap", wrap, 60, onWrap)
        ToolKey("A-", false, 48, onSmaller)
        ToolKey("A+", false, 48, onBigger)
        keys.forEach { (label, ins) ->
            ToolKey(label, false, if (label == "Tab") 56 else 48) { insert(ins, if (ins.length == 2 && label.contains(' ')) 1 else 0) }
        }
    }
}

@Composable
private fun FindBar(of: OpenFile, onClose: () -> Unit, onReveal: (Int) -> Unit) {
    val p = LocalPalette.current
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var showReplace by remember { mutableStateOf(false) }
    var opts by remember { mutableStateOf(FindOptions()) }
    var index by remember { mutableIntStateOf(-1) }
    val result = remember(query, opts, of.value.text) { FindReplace.findAll(of.value.text, query, opts) }
    val matches = result.matches
    fun jump(forward: Boolean) {
        if (matches.isEmpty()) return
        val caret = if (forward) of.value.selection.max else of.value.selection.min
        val i = FindReplace.nextIndex(matches, caret, forward)
        index = i
        matches[i].let { of.select(it.start, it.end); onReveal(it.start) }
    }
    Column(Modifier.fillMaxWidth().background(p.card).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Field("Find in file", query, { query = it; index = -1 }, mono = true) }
            IconBtn(I.close, "Close", onClose)
        }
        Txt(when {
            result.error != null -> result.error
            query.isEmpty() -> ""
            matches.isEmpty() -> "No matches"
            index >= 0 -> "${index + 1} of ${matches.size}"
            else -> "${matches.size} matches"
        }, T.secondary, if (result.error != null) p.error else p.secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            ToolKey("Previous", false, 80) { jump(false) }
            ToolKey("Next", false, 64) { jump(true) }
            ToolKey("Match case", opts.caseSensitive, 100) { opts = opts.copy(caseSensitive = !opts.caseSensitive) }
            ToolKey("Whole word", opts.wholeWord, 96) { opts = opts.copy(wholeWord = !opts.wholeWord) }
            ToolKey("Regex", opts.regex, 64) { opts = opts.copy(regex = !opts.regex) }
            ToolKey("Replace", showReplace, 76) { showReplace = !showReplace }
        }
        if (showReplace) {
            Field("Replace with", replacement, { replacement = it }, mono = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Replace", {
                    val sel = of.value.selection
                    val m = matches.firstOrNull { it.start == sel.min && it.end == sel.max } ?: matches.getOrNull(FindReplace.nextIndex(matches, sel.min, true))
                    if (m != null) {
                        val (t, caret) = FindReplace.replaceOne(of.value.text, m, replacement, query, opts)
                        of.replaceWith(t, caret)
                    }
                }, Modifier.weight(1f), solid = false, enabled = matches.isNotEmpty())
                Btn("Replace all", {
                    val (t, _) = FindReplace.replaceAll(of.value.text, query, replacement, opts)
                    of.replaceWith(t, 0)
                }, Modifier.weight(1f), solid = false, enabled = matches.isNotEmpty())
            }
        }
    }
}

@Composable
private fun GoToDialog(of: OpenFile, onClose: () -> Unit, onReveal: (Int) -> Unit) {
    val p = LocalPalette.current
    var input by remember { mutableStateOf("") }
    val total = GoTo.lineCount(of.value.text)
    val parsed = GoTo.parse(input)
    Sheet(onClose) {
        Txt("Go to line", T.section, modifier = Modifier.padding(20.dp))
        Box(Modifier.padding(horizontal = 20.dp)) { Field("Line or line:column", input, { input = it }, mono = true, keyboard = androidx.compose.ui.text.input.KeyboardType.Number) }
        Txt("Line 1 to $total", T.secondary, p.secondary, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Cancel", onClose, Modifier.weight(1f), solid = false)
            Btn("Go", {
                parsed?.let { (l, c) -> val off = GoTo.offsetOfLine(of.value.text, l, c); of.select(off, off); onReveal(off) }
                onClose()
            }, Modifier.weight(1f), enabled = parsed != null)
        }
    }
}
