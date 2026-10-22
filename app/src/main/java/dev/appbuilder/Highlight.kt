package dev.appbuilder

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

private val KEYWORDS = listOf(
    "package", "import", "class", "interface", "object", "fun", "val", "var", "override", "private", "public",
    "protected", "internal", "if", "else", "when", "for", "while", "do", "return", "true", "false", "null",
    "this", "super", "is", "as", "in", "new", "static", "final", "void", "extends", "implements", "throw",
    "try", "catch", "finally", "companion", "data", "sealed", "enum", "abstract", "open", "lateinit", "by",
    "suspend", "const", "typealias", "plugins", "dependencies", "android", "int", "long", "boolean",
).joinToString("|")

private val CODE = Regex(
    "(//[^\\n]*|/\\*[\\s\\S]*?\\*/)" +
        "|(\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*')" +
        "|(@\\w+)|\\b($KEYWORDS)\\b|\\b([A-Z]\\w*)\\b|\\b(\\d[\\d_.]*[fFLl]?)\\b"
)
private val XML = Regex("(<!--[\\s\\S]*?-->)|(\"[^\"]*\")|(</?[\\w.:-]+)|([\\w:.-]+(?==))")
private val CONF = Regex("(#[^\\n]*)|(\"(?:\\\\.|[^\"\\\\\\n])*\")")

class Highlighter(private val ext: String, private val p: Palette) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlight(text.text), OffsetMapping.Identity)

    private fun highlight(src: String): AnnotatedString = buildAnnotatedString {
        append(src)
        if (src.length > 200_000) return@buildAnnotatedString
        when (ext) {
            "kt", "java", "gradle", "kts" -> for (m in CODE.findAll(src)) {
                val c = when {
                    m.groups[1] != null -> p.comment
                    m.groups[2] != null -> p.string
                    m.groups[3] != null -> p.keyword
                    m.groups[4] != null -> p.keyword
                    m.groups[5] != null -> p.type
                    else -> p.string
                }
                addStyle(SpanStyle(color = c), m.range.first, m.range.last + 1)
            }
            "xml" -> for (m in XML.findAll(src)) {
                val c = when {
                    m.groups[1] != null -> p.comment
                    m.groups[2] != null -> p.string
                    m.groups[3] != null -> p.type
                    else -> p.keyword
                }
                addStyle(SpanStyle(color = c), m.range.first, m.range.last + 1)
            }
            "properties", "toml" -> for (m in CONF.findAll(src)) {
                addStyle(SpanStyle(color = if (m.groups[1] != null) p.comment else p.string), m.range.first, m.range.last + 1)
            }
            "json" -> for (m in Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"").findAll(src)) {
                addStyle(SpanStyle(color = p.string), m.range.first, m.range.last + 1)
            }
        }
    }
}

/** Auto-indent, auto-close brackets and skip-over of closing characters. */
fun smartEdit(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    if (!old.selection.collapsed || !new.selection.collapsed) return new
    if (new.text.length != old.text.length + 1) return new
    val i = new.selection.start - 1
    if (i < 0 || i >= new.text.length) return new
    val c = new.text[i]
    val t = new.text
    when {
        c == '\n' -> {
            val ls = t.lastIndexOf('\n', i - 1) + 1
            val prev = t.substring(ls, i)
            val indent = prev.takeWhile { it == ' ' || it == '\t' }
            val opens = prev.trimEnd().lastOrNull()?.let { it in "{([" } == true
            val extra = if (opens) "    " else ""
            val next = t.getOrNull(i + 1)
            val insert = indent + extra
            return if (opens && (next == '}' || next == ')' || next == ']')) {
                val s = t.substring(0, i + 1) + insert + "\n" + indent + t.substring(i + 1)
                TextFieldValue(s, androidx.compose.ui.text.TextRange(i + 1 + insert.length))
            } else {
                TextFieldValue(
                    t.substring(0, i + 1) + insert + t.substring(i + 1),
                    androidx.compose.ui.text.TextRange(i + 1 + insert.length),
                )
            }
        }
        c in ")]}\"" && old.text.getOrNull(i) == c -> // typed a closer that already exists: step over it
            return TextFieldValue(old.text, androidx.compose.ui.text.TextRange(i + 1))
        c in "({[\"" -> {
            val next = t.getOrNull(i + 1)
            val prevCh = t.getOrNull(i - 1)
            val okNext = next == null || next.isWhitespace() || next in ")]}"
            val okPrev = c != '"' || prevCh == null || !prevCh.isLetterOrDigit()
            if (okNext && okPrev) {
                val close = when (c) { '(' -> ')'; '[' -> ']'; '{' -> '}'; else -> '"' }
                return TextFieldValue(
                    t.substring(0, i + 1) + close + t.substring(i + 1),
                    androidx.compose.ui.text.TextRange(i + 1),
                )
            }
        }
    }
    return new
}
