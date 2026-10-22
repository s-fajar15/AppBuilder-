package dev.appbuilder

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** The editor's text and selection, free of any UI type so the logic can be unit-tested on the JVM. */
data class EditState(val text: String, val selStart: Int, val selEnd: Int) {
    val min: Int get() = minOf(selStart, selEnd)
    val max: Int get() = maxOf(selStart, selEnd)
    val collapsed: Boolean get() = selStart == selEnd
}

object EditMsg {
    const val INVALID_PATTERN = "Invalid pattern"
    const val TOO_SLOW = "The pattern is too slow for this file"
}

// ---------------------------------------------------------------- undo / redo

/**
 * Undo history. Typing or deleting one character at a time inside a word is merged into one step;
 * a pause longer than a second, a space or newline, a cursor jump, a paste or any other change starts a new step.
 * Redo is dropped as soon as a new edit is recorded. Memory is bounded by step count and total characters.
 */
class UndoStack(private val limit: Int = 200, private val maxChars: Long = 4_000_000, private val clock: () -> Long = System::currentTimeMillis) {
    private class Step(val before: EditState, var after: EditState, val kind: Int, var time: Long, var closed: Boolean)

    private val undoList = ArrayList<Step>()
    private val redoList = ArrayList<Step>()

    val canUndo: Boolean get() = undoList.isNotEmpty()
    val canRedo: Boolean get() = redoList.isNotEmpty()

    fun clear() { undoList.clear(); redoList.clear() }

    private fun kindOf(b: EditState, a: EditState): Int = when {
        b.text == a.text -> 0
        b.collapsed && a.collapsed && a.text.length == b.text.length + 1 && a.selStart == b.selStart + 1 &&
            a.text.startsWith(b.text.substring(0, b.selStart)) && a.text.endsWith(b.text.substring(b.selStart)) -> 1
        b.collapsed && a.collapsed && a.text.length == b.text.length - 1 && a.selStart == b.selStart - 1 &&
            b.text.startsWith(a.text.substring(0, a.selStart)) && b.text.endsWith(a.text.substring(a.selStart)) -> 2
        else -> 3
    }

    fun record(before: EditState, after: EditState) {
        if (before.text == after.text) return
        val now = clock()
        redoList.clear()
        val kind = kindOf(before, after)
        val last = undoList.lastOrNull()
        val boundary = kind == 1 && after.text[after.selStart - 1].let { it.isWhitespace() }
        if (last != null && !last.closed && kind in 1..2 && last.kind == kind && last.after == before && now - last.time < 1000) {
            last.after = after; last.time = now
            if (boundary) last.closed = true
        } else {
            undoList += Step(before, after, kind, now, boundary || kind == 3)
        }
        while (undoList.size > limit || (undoList.size > 1 && total() > maxChars)) undoList.removeAt(0)
    }

    private fun total(): Long = undoList.sumOf { it.before.text.length.toLong() + it.after.text.length }

    fun undo(current: EditState): EditState? {
        val s = undoList.removeLastOrNull() ?: return null
        redoList += s
        return s.before
    }

    fun redo(current: EditState): EditState? {
        val s = redoList.removeLastOrNull() ?: return null
        s.closed = true
        undoList += s
        return s.after
    }
}

// ---------------------------------------------------------------- indentation

object AutoIndent {
    private val closers = mapOf('{' to '}', '(' to ')', '[' to ']')

    private fun lineStart(t: String, offset: Int) = t.lastIndexOf('\n', offset - 1) + 1
    private fun leading(s: String) = s.takeWhile { it == ' ' || it == '\t' }

    /**
     * Call right AFTER the user typed a newline: [text] already contains it, [caret] is just behind it.
     * Returns the text with the new line indented, and the caret on that line.
     */
    fun onNewline(text: String, caret: Int, ext: String, unit: String = "    "): EditState {
        val nl = caret - 1
        if (nl < 0 || nl >= text.length || text[nl] != '\n') return EditState(text, caret, caret)
        val ls = lineStart(text, nl)
        val prev = text.substring(ls, nl)
        val indent = leading(prev)
        val trimmed = prev.trimEnd()
        val whitespaceOnly = trimmed.isEmpty()
        val last = trimmed.lastOrNull()
        val opens = if (ext.equals("xml", true)) {
            val t = trimmed.trim()
            t.startsWith("<") && t.endsWith(">") && !t.startsWith("</") && !t.startsWith("<?") && !t.startsWith("<!") && !t.endsWith("/>") &&
                !Regex("^<([\\w:.-]+)[^>]*>.*</\\1>$").matches(t)
        } else last != null && last in closers
        val extra = if (opens) unit else ""
        val next = text.getOrNull(caret)
        // Drop the spaces of a blank line we are leaving behind.
        val head = if (whitespaceOnly) text.substring(0, ls) + "\n" else text.substring(0, caret)
        val shift = text.substring(0, caret).length - head.length
        val tail = text.substring(caret)
        val newCaretBase = head.length
        return if (opens && last != null && closers[last] != null && closers[last] == next) {
            val s = head + indent + extra + "\n" + indent + tail
            EditState(s, newCaretBase + indent.length + extra.length, newCaretBase + indent.length + extra.length)
        } else {
            val s = head + indent + extra + tail
            EditState(s, newCaretBase + indent.length + extra.length, newCaretBase + indent.length + extra.length)
        }.also { check(shift >= 0) }
    }

    /** Call right after a } ) ] was typed at [caret] - 1; on a line that holds only indentation it steps one unit back. */
    fun onCloseBracket(text: String, caret: Int, typed: Char, unit: String = "    "): EditState? {
        if (typed !in "})]") return null
        val i = caret - 1
        if (i < 0 || text.getOrNull(i) != typed) return null
        val ls = lineStart(text, i)
        val before = text.substring(ls, i)
        if (before.isEmpty() || before.any { it != ' ' && it != '\t' }) return null
        val cut = when {
            before.endsWith(unit) -> unit.length
            before.endsWith("\t") -> 1
            else -> before.takeLastWhile { it == ' ' }.length.coerceAtMost(unit.length)
        }
        if (cut == 0) return null
        val s = text.substring(0, i - cut) + text.substring(i)
        return EditState(s, caret - cut, caret - cut)
    }

    /** Tab / Shift+Tab. A caret inserts one unit (or moves to the next tab stop); a selection over several lines shifts them all. */
    fun indentSelection(text: String, selStart: Int, selEnd: Int, unit: String = "    ", outdent: Boolean = false): EditState {
        val a = minOf(selStart, selEnd).coerceIn(0, text.length)
        val b = maxOf(selStart, selEnd).coerceIn(0, text.length)
        val multi = text.substring(a, b).contains('\n')
        if (a == b || !multi) {
            if (a != b && !outdent) { // a single-line selection is replaced by the indent, like a normal key press
                val s = text.substring(0, a) + unit + text.substring(b)
                return EditState(s, a + unit.length, a + unit.length)
            }
            if (!outdent) {
                val col = a - lineStart(text, a)
                val ins = if (unit == "\t") "\t" else " ".repeat(unit.length - col % unit.length)
                return EditState(text.substring(0, a) + ins + text.substring(a), a + ins.length, a + ins.length)
            }
            val ls = lineStart(text, a)
            val cut = cutIndent(text, ls, unit)
            if (cut == 0) return EditState(text, selStart, selEnd)
            val s = text.substring(0, ls) + text.substring(ls + cut)
            val nc = maxOf(ls, a - cut)
            val ne = maxOf(ls, b - cut)
            return EditState(s, if (selStart <= selEnd) nc else ne, if (selStart <= selEnd) ne else nc)
        }
        val firstLs = lineStart(text, a)
        var lastEnd = b
        if (b > a && text[b - 1] == '\n') lastEnd = b - 1 // a selection ending at a line start does not include that line
        val sb = StringBuilder()
        var pos = firstLs
        var newA = a; var newB = b
        var first = true
        while (pos <= lastEnd) {
            val le = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
            val delta: Int
            if (outdent) {
                val cut = cutIndent(text, pos, unit)
                sb.append(text, pos + cut, le); delta = -cut
            } else {
                sb.append(unit); sb.append(text, pos, le); delta = unit.length
            }
            if (first) { newA = maxOf(pos, a + delta).let { if (outdent) maxOf(pos, a + delta) else a + delta }; first = false } else if (pos <= a) newA += delta
            if (pos <= b) newB += delta
            if (le < text.length) sb.append('\n')
            pos = le + 1
            if (le >= text.length) break
        }
        val prefix = text.substring(0, firstLs)
        val suffix = if (pos <= text.length) text.substring(minOf(pos, text.length)) else ""
        val s = prefix + sb + suffix
        val outA = newA.coerceIn(0, s.length); val outB = newB.coerceIn(0, s.length)
        return if (selStart <= selEnd) EditState(s, outA, outB) else EditState(s, outB, outA)
    }

    private fun cutIndent(text: String, ls: Int, unit: String): Int {
        if (text.startsWith(unit, ls)) return unit.length
        if (text.startsWith("\t", ls)) return 1
        var n = 0
        while (n < unit.length && text.getOrNull(ls + n) == ' ') n++
        return n
    }

    /** The indentation the file already uses: "\t", two spaces or four spaces (the default). */
    fun detectUnit(text: String): String {
        var tabs = 0; var spaced = 0
        val diffs = IntArray(9)
        var prev = 0
        for (line in text.lineSequence().take(3000)) {
            if (line.isBlank()) continue
            if (line.startsWith("\t")) { tabs++; continue }
            val n = line.takeWhile { it == ' ' }.length
            if (n > 0) spaced++
            val d = n - prev
            if (d in 1..8) diffs[d]++
            prev = n
        }
        if (tabs > spaced && tabs > 2) return "\t"
        return if (diffs[2] > diffs[4] && diffs[2] > 2) "  " else "    "
    }
}

// ---------------------------------------------------------------- find and replace

data class FindOptions(val caseSensitive: Boolean = false, val wholeWord: Boolean = false, val regex: Boolean = false)
data class FindMatch(val start: Int, val end: Int)
data class FindResult(val matches: List<FindMatch>, val error: String?)

private class DeadlineSeq(private val s: CharSequence, private val deadline: Long) : CharSequence {
    private var ticks = 0
    override val length: Int get() = s.length
    override fun get(index: Int): Char {
        if (++ticks and 0xFFFF == 0 && System.currentTimeMillis() > deadline) throw IllegalStateException("timeout")
        return s[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = DeadlineSeq(s.subSequence(startIndex, endIndex), deadline)
    override fun toString(): String = s.toString()
}

object FindReplace {
    private fun compile(query: String, o: FindOptions): Pattern {
        var src = if (o.regex) query else Pattern.quote(query)
        if (o.wholeWord) src = "(?<![\\p{L}\\p{N}_])(?:$src)(?![\\p{L}\\p{N}_])"
        var flags = Pattern.MULTILINE
        if (!o.caseSensitive) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        return Pattern.compile(src, flags)
    }

    fun findAll(text: String, query: String, opts: FindOptions, limit: Int = 5000): FindResult {
        if (query.isEmpty()) return FindResult(emptyList(), null)
        val p = try { compile(query, opts) } catch (e: PatternSyntaxException) { return FindResult(emptyList(), EditMsg.INVALID_PATTERN) }
        val out = ArrayList<FindMatch>()
        try {
            val m = p.matcher(DeadlineSeq(text, System.currentTimeMillis() + 2000))
            while (out.size < limit && m.find()) {
                if (m.end() == m.start()) continue // an empty match is not a place the user can see
                out += FindMatch(m.start(), m.end())
            }
        } catch (e: IllegalStateException) { return FindResult(out, EditMsg.TOO_SLOW) } catch (e: StackOverflowError) { return FindResult(out, EditMsg.TOO_SLOW) }
        return FindResult(out, null)
    }

    /** Index into [matches] of the next (or previous) match from [caret], wrapping around; -1 when there are none. */
    fun nextIndex(matches: List<FindMatch>, caret: Int, forward: Boolean): Int {
        if (matches.isEmpty()) return -1
        return if (forward) matches.indexOfFirst { it.start >= caret }.let { if (it < 0) 0 else it }
        else matches.indexOfLast { it.start < caret }.let { if (it < 0) matches.lastIndex else it }
    }

    private fun expand(m: Matcher, replacement: String, regex: Boolean): String {
        if (!regex) return replacement
        val sb = StringBuilder()
        var i = 0
        while (i < replacement.length) {
            val c = replacement[i]
            if (c == '\\' && i + 1 < replacement.length) {
                when (val n = replacement[i + 1]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); '\\' -> sb.append('\\'); '$' -> sb.append('$')
                    else -> sb.append('\\').append(n)
                }
                i += 2
            } else if (c == '$' && i + 1 < replacement.length && replacement[i + 1].isDigit()) {
                val g = replacement[i + 1] - '0'
                if (g <= m.groupCount()) sb.append(m.group(g) ?: "")
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    /** Replaces one match. Returns the new text and the caret position behind the replacement. */
    fun replaceOne(text: String, match: FindMatch, replacement: String, query: String, opts: FindOptions): Pair<String, Int> {
        if (match.start < 0 || match.end > text.length || match.start > match.end) return text to match.start.coerceIn(0, text.length)
        val rep = try {
            val m = compile(query, opts).matcher(text)
            m.region(match.start, match.end); m.useTransparentBounds(true); m.useAnchoringBounds(false)
            if (m.matches()) expand(m, replacement, opts.regex) else replacement
        } catch (e: PatternSyntaxException) { replacement }
        return (text.substring(0, match.start) + rep + text.substring(match.end)) to (match.start + rep.length)
    }

    fun replaceAll(text: String, query: String, replacement: String, opts: FindOptions): Pair<String, Int> {
        if (query.isEmpty()) return text to 0
        val p = try { compile(query, opts) } catch (e: PatternSyntaxException) { return text to 0 }
        val sb = StringBuilder(); var last = 0; var count = 0
        try {
            val m = p.matcher(DeadlineSeq(text, System.currentTimeMillis() + 2000))
            while (m.find()) {
                if (m.end() == m.start()) continue
                sb.append(text, last, m.start()).append(expand(m, replacement, opts.regex))
                last = m.end(); count++
            }
        } catch (e: IllegalStateException) { return text to 0 } catch (e: StackOverflowError) { return text to 0 }
        if (count == 0) return text to 0
        sb.append(text, last, text.length)
        return sb.toString() to count
    }
}

// ---------------------------------------------------------------- go to line

object GoTo {
    fun lineCount(text: String): Int = text.count { it == '\n' } + 1

    /** Offset of 1-based [line] / [column]; both are clamped to what exists. */
    fun offsetOfLine(text: String, line: Int, column: Int = 1): Int {
        val target = line.coerceIn(1, lineCount(text))
        var off = 0; var n = 1
        while (n < target) { off = text.indexOf('\n', off) + 1; n++ }
        val end = text.indexOf('\n', off).let { if (it < 0) text.length else it }
        return (off + (column - 1).coerceAtLeast(0)).coerceAtMost(end)
    }

    fun lineOf(text: String, offset: Int): Int = text.substring(0, offset.coerceIn(0, text.length)).count { it == '\n' } + 1

    fun columnOf(text: String, offset: Int): Int {
        val o = offset.coerceIn(0, text.length)
        return o - (text.lastIndexOf('\n', o - 1) + 1) + 1
    }

    /** "12", "12:5" or " 12 : 5 " -> line and column; null for anything else, zero or negative. */
    fun parse(input: String): Pair<Int, Int>? {
        val m = Regex("^\\s*(\\d{1,9})\\s*(?::\\s*(\\d{1,9}))?\\s*$").find(input) ?: return null
        val line = m.groupValues[1].toInt(); val col = m.groupValues[2].ifEmpty { "1" }.toInt()
        return if (line < 1 || col < 1) null else line to col
    }
}
