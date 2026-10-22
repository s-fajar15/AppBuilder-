package dev.appbuilder

/** Thrown by [Json.parse]; the message names the character position of the problem. */
class JsonException(msg: String) : Exception(msg)

/**
 * A small, strict JSON reader and writer with no Android dependency.
 * Objects become LinkedHashMap<String, Any?>, arrays List<Any?>, integers Long, other numbers Double.
 */
object Json {
    fun parse(text: String): Any? {
        val p = JsonParser(text)
        p.skipBom()
        p.ws()
        val v = p.value(0)
        p.ws()
        if (!p.atEnd()) p.fail("Unexpected content after the value")
        return v
    }

    fun stringify(v: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        write(sb, v, pretty, 0)
        return sb.toString()
    }

    // ---- typed access; every helper returns null when the key is missing or has another type ----

    @Suppress("UNCHECKED_CAST")
    fun obj(m: Any?, key: String): Map<String, Any?>? = (m as? Map<*, *>)?.get(key) as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun arr(m: Any?, key: String): List<Any?>? = (m as? Map<*, *>)?.get(key) as? List<Any?>

    fun str(m: Any?, key: String): String? = (m as? Map<*, *>)?.get(key) as? String
    fun long(m: Any?, key: String): Long? = ((m as? Map<*, *>)?.get(key) as? Number)?.toLong()
    fun int(m: Any?, key: String): Int? = ((m as? Map<*, *>)?.get(key) as? Number)?.toInt()
    fun bool(m: Any?, key: String): Boolean? = (m as? Map<*, *>)?.get(key) as? Boolean

    // ---- writer ----

    private fun write(sb: StringBuilder, v: Any?, pretty: Boolean, depth: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v.toString())
            is Double -> if (v.isNaN() || v.isInfinite()) sb.append("null") else sb.append(v.toString())
            is Float -> if (v.isNaN() || v.isInfinite()) sb.append("null") else sb.append(v.toString())
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) newline(sb, depth + 1)
                    quote(sb, k.toString())
                    sb.append(if (pretty) ": " else ":")
                    write(sb, x, pretty, depth + 1)
                }
                if (pretty) newline(sb, depth)
                sb.append('}')
            }
            is Iterable<*> -> writeList(sb, v.toList(), pretty, depth)
            is Array<*> -> writeList(sb, v.toList(), pretty, depth)
            else -> quote(sb, v.toString())
        }
    }

    private fun writeList(sb: StringBuilder, l: List<Any?>, pretty: Boolean, depth: Int) {
        if (l.isEmpty()) { sb.append("[]"); return }
        sb.append('[')
        l.forEachIndexed { i, x ->
            if (i > 0) sb.append(',')
            if (pretty) newline(sb, depth + 1)
            write(sb, x, pretty, depth + 1)
        }
        if (pretty) newline(sb, depth)
        sb.append(']')
    }

    private fun newline(sb: StringBuilder, depth: Int) {
        sb.append('\n')
        repeat(depth) { sb.append("  ") }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') sb.append("\\u").append("%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}

private class JsonParser(private val s: String) {
    private var i = 0

    fun atEnd() = i >= s.length
    fun skipBom() { if (s.startsWith("﻿")) i = 1 }

    fun fail(msg: String): Nothing = throw JsonException("$msg at position $i")

    fun ws() {
        while (i < s.length) {
            val c = s[i]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else break
        }
    }

    fun value(depth: Int): Any? {
        if (depth > 512) fail("Nesting is too deep")
        ws()
        if (i >= s.length) fail("Unexpected end of text")
        return when (val c = s[i]) {
            '{' -> obj(depth)
            '[' -> arr(depth)
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c in '0'..'9') number() else fail("Unexpected character '$c'")
        }
    }

    private fun literal(word: String, v: Any?): Any? {
        if (!s.startsWith(word, i)) fail("Invalid literal")
        i += word.length
        return v
    }

    private fun obj(depth: Int): Map<String, Any?> {
        i++ // {
        val m = LinkedHashMap<String, Any?>()
        ws()
        if (i < s.length && s[i] == '}') { i++; return m }
        while (true) {
            ws()
            if (i >= s.length || s[i] != '"') fail("Expected a string key")
            val k = string()
            ws()
            if (i >= s.length || s[i] != ':') fail("Expected ':'")
            i++
            m[k] = value(depth + 1)
            ws()
            if (i >= s.length) fail("Unexpected end of text")
            when (s[i]) {
                ',' -> i++
                '}' -> { i++; return m }
                else -> fail("Expected ',' or '}'")
            }
        }
    }

    private fun arr(depth: Int): List<Any?> {
        i++ // [
        val l = ArrayList<Any?>()
        ws()
        if (i < s.length && s[i] == ']') { i++; return l }
        while (true) {
            l += value(depth + 1)
            ws()
            if (i >= s.length) fail("Unexpected end of text")
            when (s[i]) {
                ',' -> i++
                ']' -> { i++; return l }
                else -> fail("Expected ',' or ']'")
            }
        }
    }

    private fun string(): String {
        i++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) fail("Unterminated string")
            val c = s[i++]
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> {
                    if (i >= s.length) fail("Unterminated string")
                    when (val e = s[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) fail("Invalid unicode escape")
                            val hex = s.substring(i, i + 4)
                            val code = hex.toIntOrNull(16)
                            if (code == null || hex.any { it == '+' || it == '-' }) fail("Invalid unicode escape")
                            sb.append(code.toChar())
                            i += 4
                        }
                        else -> { i--; fail("Invalid escape '\\$e'") }
                    }
                }
                c < ' ' -> { i--; fail("Unescaped control character in string") }
                else -> sb.append(c)
            }
        }
    }

    private fun number(): Any {
        val start = i
        if (s[i] == '-') i++
        if (i >= s.length) fail("Invalid number")
        if (s[i] == '0') i++
        else if (s[i] in '1'..'9') { while (i < s.length && s[i] in '0'..'9') i++ }
        else fail("Invalid number")
        var real = false
        if (i < s.length && s[i] == '.') {
            real = true; i++
            val d = i
            while (i < s.length && s[i] in '0'..'9') i++
            if (i == d) fail("Invalid number")
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            real = true; i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            val d = i
            while (i < s.length && s[i] in '0'..'9') i++
            if (i == d) fail("Invalid number")
        }
        val t = s.substring(start, i)
        return if (real) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
    }
}
