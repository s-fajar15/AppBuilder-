package dev.appbuilder

/*
 * Compose preview without compiling the project.
 *
 * AppBuilder reads the project's Kotlin sources, finds @Preview functions and interprets a pragmatic subset of the
 * Compose DSL (layouts, Text, Button, modifiers, constant values, project composables are inlined). The result is a
 * tree of [PNode] that the app draws with its own widgets. It is an approximation of layout and text; anything it
 * cannot understand becomes a placeholder node plus a warning, never an exception.
 */

sealed interface PValue {
    data class Str(val s: String) : PValue
    /** [unit] is "dp", "sp", "f" (float literal) or "" (plain number). */
    data class Num(val n: Double, val unit: String) : PValue
    data class Bool(val b: Boolean) : PValue
    data class ColorV(val argb: Long) : PValue
    /** A name the parser could not resolve to a constant, e.g. Icons.Default.Add or MaterialTheme.colorScheme.primary. */
    data class Ref(val name: String) : PValue
    /** A call that is kept as data, e.g. Arrangement.spacedBy(8.dp) or RoundedCornerShape(12.dp). */
    data class PCall(val name: String, val args: List<PValue>, val named: Map<String, PValue>) : PValue
    data class Lst(val items: List<PValue>) : PValue
}

data class PMod(val name: String, val positional: List<PValue>, val named: Map<String, PValue>)

class PNode(
    val name: String,
    val line: Int,
    val positional: List<PValue>,
    val args: Map<String, PValue>,
    val modifier: List<PMod>,
    /** Content of the trailing lambda. */
    val children: List<PNode>,
    /** Named composable lambdas: topBar, bottomBar, floatingActionButton, title, navigationIcon, actions, label, placeholder, leadingIcon, trailingIcon, icon, text ... */
    val slots: Map<String, List<PNode>>,
    val placeholder: Boolean = false,
)

data class PreviewDef(
    val name: String, val file: String, val line: Int, val title: String,
    val widthDp: Int?, val heightDp: Int?, val showBackground: Boolean, val backgroundColor: Long?,
    val night: Boolean, val showSystemUi: Boolean, val root: List<PNode>, val warnings: List<String>,
)

/** Fixed warning templates (translated in [PreviewTexts]). */
object PreviewMsg {
    fun cannotPreview(n: String) = "Cannot preview $n"
    fun needsParams(n: String) = "Skipped $n: it has parameters without default values"
    fun unreadable(line: Int) = "Could not read the code at line $line"
    fun notConstant(line: Int) = "The condition at line $line is not constant: showing the first branch"
    fun sampleData(line: Int) = "Using sample data for the list at line $line"
    const val TOO_MANY = "Too many elements: the preview was cut"
    fun recursive(n: String) = "Skipped a recursive call to $n"
    fun missingString(k: String) = "String resource $k was not found"
    fun customAnnotation(n: String) = "Preview $n uses a custom annotation, which is not supported"
}

object ComposePreviewParser {
    private val previewRe = Regex("@(?:androidx\\.compose\\.ui\\.tooling\\.preview\\.)?Preview\\b")

    fun hasPreviews(source: String): Boolean = previewRe.containsMatchIn(source) && source.contains("fun ")

    fun parseStrings(xml: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (m in Regex("<string\\s+[^>]*?name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            var v = m.groupValues[2].trim()
            if (v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) v = v.substring(1, v.length - 1)
            v = v.replace("\\n", "\n").replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
            out[m.groupValues[1]] = v
        }
        return out
    }

    fun findPreviews(files: Map<String, String>, strings: Map<String, String> = emptyMap()): List<PreviewDef> {
        return try { Engine(files, strings).run() } catch (e: Throwable) { emptyList() }
    }

}

// ====================================================================== lexer

private enum class K { ID, NUM, STR, CH, SYM, EOF }

private class Tok(
    val k: K, val s: String, val line: Int, val nl: Boolean,
    val num: Double = 0.0, val hex: Boolean = false, val suffix: String = "", val raw: Boolean = false,
)

private class ParseError(val line: Int, msg: String) : Exception(msg)

private val SYMS3 = setOf("...")
private val SYMS2 = setOf("?.", "?:", "!!", "::", "->", "==", "!=", "<=", ">=", "&&", "||", "..", "+=", "-=", "*=", "/=", "++", "--")

private fun lex(src: String): List<Tok> {
    val out = ArrayList<Tok>()
    var i = 0; var line = 1; var nl = false
    val n = src.length
    fun isIdStart(c: Char) = c == '_' || c.isLetter()
    while (i < n) {
        val c = src[i]
        if (c == '\n') { line++; nl = true; i++; continue }
        if (c.isWhitespace()) { i++; continue }
        if (c == '/' && i + 1 < n && src[i + 1] == '/') { while (i < n && src[i] != '\n') i++; continue }
        if (c == '/' && i + 1 < n && src[i + 1] == '*') {
            var depth = 1; i += 2
            while (i < n && depth > 0) {
                if (src[i] == '\n') line++
                if (src.startsWith("/*", i)) { depth++; i += 2 } else if (src.startsWith("*/", i)) { depth--; i += 2 } else i++
            }
            continue
        }
        val startLine = line
        if (c == '"') {
            val triple = src.startsWith("\"\"\"", i)
            if (triple) {
                i += 3; val st = i
                while (i < n && !src.startsWith("\"\"\"", i)) { if (src[i] == '\n') line++; i++ }
                out += Tok(K.STR, src.substring(st, minOf(i, n)), startLine, nl, raw = true); nl = false
                i = minOf(n, i + 3)
            } else {
                i++; val st = i
                var depth = 0
                while (i < n) {
                    val d = src[i]
                    if (d == '\n') break
                    if (depth == 0 && d == '\\') { i += 2; continue }
                    if (d == '$' && i + 1 < n && src[i + 1] == '{' && depth == 0) { depth = 1; i += 2; continue }
                    if (depth > 0) {
                        if (d == '{') depth++ else if (d == '}') depth--
                        else if (d == '"') { i++; while (i < n && src[i] != '"' && src[i] != '\n') { if (src[i] == '\\') i++; i++ } }
                        i++; continue
                    }
                    if (d == '"') break
                    i++
                }
                out += Tok(K.STR, src.substring(st, minOf(i, n)), startLine, nl, raw = false); nl = false
                i = minOf(n, i + 1)
            }
            continue
        }
        if (c == '\'') {
            var j = i + 1
            while (j < n && src[j] != '\'' && src[j] != '\n') { if (src[j] == '\\') j++; j++ }
            out += Tok(K.CH, src.substring(i + 1, minOf(j, n)), line, nl); nl = false
            i = minOf(n, j + 1); continue
        }
        if (c.isDigit()) {
            var j = i
            if (c == '0' && i + 1 < n && (src[i + 1] == 'x' || src[i + 1] == 'X')) {
                j = i + 2
                while (j < n && (src[j].isLetterOrDigit() || src[j] == '_')) j++
                var digits = src.substring(i + 2, j).replace("_", "")
                var suf = ""
                if (digits.endsWith("L") || digits.endsWith("l")) { digits = digits.dropLast(1); suf = "L" }
                out += Tok(K.NUM, src.substring(i, j), line, nl, num = (digits.toLongOrNull(16) ?: 0L).toDouble(), hex = true, suffix = suf); nl = false
                i = j; continue
            }
            while (j < n && (src[j].isDigit() || src[j] == '_')) j++
            if (j + 1 < n && src[j] == '.' && src[j + 1].isDigit()) { j++; while (j < n && (src[j].isDigit() || src[j] == '_')) j++ }
            if (j < n && (src[j] == 'e' || src[j] == 'E') && j + 1 < n && (src[j + 1].isDigit() || ((src[j + 1] == '-' || src[j + 1] == '+') && j + 2 < n && src[j + 2].isDigit()))) {
                j += 2; while (j < n && src[j].isDigit()) j++
            }
            val numText = src.substring(i, j).replace("_", "")
            var suf = ""
            if (j < n && (src[j] == 'f' || src[j] == 'F')) { suf = "f"; j++ } else if (j < n && (src[j] == 'L')) { suf = "L"; j++ }
            out += Tok(K.NUM, numText, line, nl, num = numText.toDoubleOrNull() ?: 0.0, suffix = suf); nl = false
            i = j; continue
        }
        if (isIdStart(c)) {
            var j = i
            while (j < n && (src[j] == '_' || src[j].isLetterOrDigit())) j++
            out += Tok(K.ID, src.substring(i, j), line, nl); nl = false
            i = j; continue
        }
        if (c == '`') {
            val j = src.indexOf('`', i + 1).let { if (it < 0) n else it }
            out += Tok(K.ID, src.substring(i + 1, j), line, nl); nl = false
            i = minOf(n, j + 1); continue
        }
        val three = if (i + 3 <= n) src.substring(i, i + 3) else ""
        val two = if (i + 2 <= n) src.substring(i, i + 2) else ""
        when {
            three in SYMS3 -> { out += Tok(K.SYM, three, line, nl); i += 3 }
            two in SYMS2 -> { out += Tok(K.SYM, two, line, nl); i += 2 }
            else -> { out += Tok(K.SYM, c.toString(), line, nl); i++ }
        }
        nl = false
    }
    out += Tok(K.EOF, "", line, true)
    return out
}

// ====================================================================== AST

private class Arg(val name: String?, val e: E)

private sealed class E { var line: Int = 0 }
private class ELit(val v: Any?) : E()
private class EStr(val raw: String, val isRaw: Boolean) : E()
private class EName(val name: String) : E()
private class EMember(val target: E, val name: String) : E()
private class ECall(val callee: E, val args: List<Arg>, val lambda: ELambda?) : E()
private class EBin(val op: String, val l: E, val r: E) : E()
private class EUn(val op: String, val e: E) : E()
private class EIf(val cond: E, val then: List<S>, val els: List<S>?) : E()
private class EWhen(val subject: E?, val branches: List<Pair<List<E>?, List<S>>>) : E()
private class ELambda(val params: List<String>, val body: List<S>) : E()
private class EIndex(val target: E, val index: E) : E()

private sealed class S
private class SVal(val name: String, val e: E?) : S()
private class SExpr(val e: E) : S()
private class SFor(val v: String, val iter: E, val body: List<S>) : S()

private class Param(val name: String, val type: String, val default: E?)
private class FunDef(val name: String, val file: String, val line: Int, val composable: Boolean, val params: List<Param>, val body: List<S>)

// ====================================================================== parser

private class Parser(val t: List<Tok>, var i: Int = 0) {
    fun peek(o: Int = 0): Tok = t[minOf(i + o, t.lastIndex)]
    fun next(): Tok = t[minOf(i, t.lastIndex)].also { if (i < t.lastIndex) i++ }
    fun isSym(s: String, o: Int = 0) = peek(o).let { it.k == K.SYM && it.s == s }
    fun isId(s: String, o: Int = 0) = peek(o).let { it.k == K.ID && it.s == s }
    fun accept(s: String): Boolean { if (isSym(s)) { i++; return true }; return false }
    fun expect(s: String) { if (!accept(s)) throw ParseError(peek().line, "Expected $s") }
    fun atEnd() = peek().k == K.EOF

    /** Skips a balanced (...) / {...} / [...] group; the opening token is current. */
    fun skipGroup() {
        val open = next().s
        val close = when (open) { "(" -> ")"; "{" -> "}"; "[" -> "]"; else -> return }
        var depth = 1
        while (!atEnd() && depth > 0) {
            val tk = next()
            if (tk.k == K.SYM) { if (tk.s == open) depth++ else if (tk.s == close) depth-- }
        }
    }

    /** Skips a type up to a top-level , ) = or { (generics and function types included). */
    fun skipType() {
        var angle = 0; var paren = 0
        while (!atEnd()) {
            val tk = peek()
            if (tk.k == K.SYM) {
                when (tk.s) {
                    "<" -> angle++
                    ">" -> if (angle > 0) angle-- else return
                    "(" -> paren++
                    ")" -> if (paren > 0) paren-- else return
                    "," -> if (angle == 0 && paren == 0) return
                    "=", "{", "}" -> if (angle == 0 && paren == 0) return
                    ";" -> return
                }
            }
            if (tk.nl && angle == 0 && paren == 0 && i > 0 && peek(-1).let { !(it.k == K.SYM && (it.s == "->" || it.s == ",")) }) {
                if (!(tk.k == K.SYM && tk.s == "->")) return
            }
            i++
        }
    }

    // ---- statements

    fun block(): List<S> {
        expect("{")
        val out = ArrayList<S>()
        while (!atEnd() && !isSym("}")) {
            val before = i
            accept(";")
            if (isSym("}")) break
            stmt()?.let { out += it }
            if (i == before) i++ // always make progress
        }
        expect("}")
        return out
    }

    private fun blockOrStmt(): List<S> =
        if (isSym("{")) block() else listOfNotNull(stmt())

    private fun annotationsSkip() {
        while (isSym("@")) {
            i++
            if (peek().k == K.ID) { i++; while (isSym(".") && peek(1).k == K.ID) i += 2 }
            if (isSym(":") ) { i++; if (peek().k == K.ID) i++ }
            if (isSym("(") && !peek().nl) skipGroup()
        }
    }

    fun stmt(): S? {
        annotationsSkip()
        val tk = peek()
        if (tk.k == K.ID) when (tk.s) {
            "val", "var" -> {
                i++
                if (isSym("(")) { skipGroup(); if (accept("=")) return SExpr(expr()); return null }
                val name = next().s
                if (accept(":")) skipType()
                if (accept("=")) return SVal(name, expr())
                if (isId("by")) { i++; return SVal(name, expr()) }
                return SVal(name, null)
            }
            "for" -> {
                i++; expect("(")
                val v = if (isSym("(")) { skipGroup(); "_" } else next().s
                if (isId("in")) i++
                val iter = expr()
                expect(")")
                return SFor(v, iter, blockOrStmt())
            }
            "return" -> { i++; if (!peek().nl && !isSym("}")) expr(); return null }
            "break", "continue" -> { i++; return null }
            "while" -> { i++; if (isSym("(")) skipGroup(); if (isSym("{")) skipGroup(); return null }
            "fun" -> { skipLocalFun(); return null }
            "class", "object", "interface" -> { while (!atEnd() && !isSym("{")) i++; if (isSym("{")) skipGroup(); return null }
        }
        val e = expr()
        val nx = peek()
        if (nx.k == K.SYM && !nx.nl && nx.s in setOf("=", "+=", "-=", "*=", "/=")) { i++; expr(); return null }
        if (nx.k == K.SYM && !nx.nl && (nx.s == "++" || nx.s == "--")) { i++; return null }
        return SExpr(e)
    }

    private fun skipLocalFun() {
        i++
        while (!atEnd() && !isSym("(")) i++
        if (isSym("(")) skipGroup()
        if (accept(":")) skipType()
        if (isSym("{")) skipGroup() else if (accept("=")) expr()
    }

    // ---- expressions

    private val binLevels = listOf(
        setOf("||"), setOf("&&"), setOf("==", "!="), setOf("<", ">", "<=", ">="), setOf("?:"), setOf(".."), setOf("+", "-"), setOf("*", "/", "%"),
    )

    fun expr(): E = binary(0)

    private fun binary(level: Int): E {
        if (level >= binLevels.size) return unary()
        var l = binary(level + 1)
        while (true) {
            val tk = peek()
            val op = when {
                tk.k == K.SYM && tk.s in binLevels[level] -> tk.s
                level == 4 && tk.k == K.ID && tk.s in setOf("or", "and", "shl", "shr", "xor", "to", "until", "downTo", "step", "in") && !tk.nl -> tk.s
                else -> null
            } ?: break
            // + - * / on a new line start a new statement; the rest may continue the expression
            if (tk.nl && op in setOf("+", "-", "*", "/", "%", "<", ">", "..")) break
            val ln = tk.line
            i++
            if (tk.k == K.ID && op == "in") { } // handled as generic op
            val r = binary(level + 1)
            l = EBin(op, l, r).also { it.line = ln }
        }
        return l
    }

    private fun unary(): E {
        val tk = peek()
        if (tk.k == K.SYM && (tk.s == "!" || tk.s == "-" || tk.s == "+")) { i++; return EUn(tk.s, unary()).also { it.line = tk.line } }
        if (tk.k == K.ID && tk.s == "await") { }
        return postfix()
    }

    private fun tryGenerics(): Boolean {
        if (!isSym("<") || peek().nl) return false
        var j = i + 1; var depth = 1
        while (j < t.size && depth > 0 && j - i < 40) {
            val tk = t[j]
            if (tk.k == K.SYM) {
                when (tk.s) { "<" -> depth++; ">" -> depth--; "," , ".", "?", "*", "(", ")", "->" -> {}; else -> return false }
            } else if (tk.k != K.ID) return false
            j++
        }
        if (depth != 0) return false
        val after = t[minOf(j, t.lastIndex)]
        if (after.k == K.SYM && (after.s == "(" || after.s == "{") && !after.nl) { i = j; return true }
        return false
    }

    private fun postfix(): E {
        var e = primary()
        while (true) {
            val tk = peek()
            if (tk.k != K.SYM) break
            when {
                (tk.s == "." || tk.s == "?.") -> {
                    i++
                    val nm = next()
                    if (nm.k != K.ID) throw ParseError(nm.line, "Expected a name")
                    e = EMember(e, nm.s).also { it.line = nm.line }
                    tryGenerics()
                }
                tk.s == "(" && !tk.nl -> {
                    val args = args()
                    var lam: ELambda? = null
                    if (isSym("{") && !peek().nl) lam = lambda()
                    e = ECall(e, args, lam).also { it.line = tk.line }
                }
                tk.s == "{" && !tk.nl && (e is EName || e is EMember || (e is ECall && e.lambda == null && false)) -> {
                    e = ECall(e, emptyList(), lambda()).also { it.line = tk.line }
                }
                tk.s == "<" && !tk.nl && (e is EName || e is EMember) && tryGenerics() -> {}
                tk.s == "[" && !tk.nl -> { i++; val idx = expr(); expect("]"); e = EIndex(e, idx).also { it.line = tk.line } }
                tk.s == "!!" -> i++
                tk.s == "::" -> { i++; if (peek().k == K.ID) i++ }
                else -> break
            }
        }
        if (isId("as") && !peek().nl) { i++; accept("?"); skipType() }
        return e
    }

    private fun args(): List<Arg> {
        expect("(")
        val out = ArrayList<Arg>()
        while (!atEnd() && !isSym(")")) {
            val before = i
            accept("*")
            if (peek().k == K.ID && isSym("=", 1)) {
                val nm = next().s; i++
                out += Arg(nm, expr())
            } else out += Arg(null, expr())
            if (!accept(",")) break
            if (i == before) i++
        }
        expect(")")
        return out
    }

    fun lambda(): ELambda {
        val ln = peek().line
        expect("{")
        val params = ArrayList<String>()
        // look ahead for `a, b ->` (optionally typed) or `(a, b) ->`
        var j = i; var ok = false; val names = ArrayList<String>()
        loop@ while (j < t.size) {
            val tk = t[j]
            when {
                tk.k == K.ID -> { names += tk.s; j++
                    if (t[j].k == K.SYM && t[j].s == ":") { j++; var d = 0
                        while (j < t.size && !(d == 0 && t[j].k == K.SYM && (t[j].s == "," || t[j].s == "->"))) { if (t[j].k == K.SYM && (t[j].s == "<" || t[j].s == "(")) d++; if (t[j].k == K.SYM && (t[j].s == ">" || t[j].s == ")")) d--; if (t[j].k == K.EOF) break@loop; j++ } }
                }
                tk.k == K.SYM && tk.s == "(" -> { var d = 1; j++; while (j < t.size && d > 0) { if (t[j].k == K.SYM && t[j].s == "(") d++; if (t[j].k == K.SYM && t[j].s == ")") d--; if (t[j].k == K.EOF) break@loop; j++ }; names += "_" }
                else -> break@loop
            }
            if (t[j].k == K.SYM && t[j].s == ",") { j++; continue }
            if (t[j].k == K.SYM && t[j].s == "->") { ok = true; j++ }
            break
        }
        if (ok) { params += names; i = j }
        val body = ArrayList<S>()
        while (!atEnd() && !isSym("}")) {
            val before = i
            accept(";")
            if (isSym("}")) break
            stmt()?.let { body += it }
            if (i == before) i++
        }
        expect("}")
        return ELambda(params, body).also { it.line = ln }
    }

    private fun primary(): E {
        val tk = next()
        val ln = tk.line
        return when (tk.k) {
            K.NUM -> ELit(if (tk.hex) PValue.Num(tk.num, "hex") else PValue.Num(tk.num, if (tk.suffix == "f") "f" else "")).also { it.line = ln }
            K.STR -> EStr(tk.s, tk.raw).also { it.line = ln }
            K.CH -> ELit(PValue.Str(tk.s)).also { it.line = ln }
            K.ID -> when (tk.s) {
                "true" -> ELit(PValue.Bool(true))
                "false" -> ELit(PValue.Bool(false))
                "null" -> ELit(null)
                "if" -> ifExpr(ln)
                "when" -> whenExpr(ln)
                "object" -> { while (!atEnd() && !isSym("{")) i++; if (isSym("{")) skipGroup(); ELit(null) }
                "this" -> EName("this")
                else -> EName(tk.s)
            }.also { it.line = ln }
            K.SYM -> when (tk.s) {
                "(" -> { val e = expr(); expect(")"); e }
                "{" -> { i--; lambda() }
                "@" -> { i--; annotationsSkip(); primary() }
                "[" -> { val items = ArrayList<Arg>(); while (!atEnd() && !isSym("]")) { items += Arg(null, expr()); if (!accept(",")) break }; expect("]"); ECall(EName("listOf"), items, null).also { it.line = ln } }
                else -> throw ParseError(ln, "Unexpected ${tk.s}")
            }
            K.EOF -> throw ParseError(ln, "Unexpected end")
        }
    }

    private fun ifExpr(ln: Int): E {
        expect("(")
        val c = expr()
        expect(")")
        val th = blockOrStmt()
        var el: List<S>? = null
        if (isId("else")) { i++; el = if (isId("if")) { val l2 = next().line; listOf(SExpr(ifExpr(l2))) } else blockOrStmt() }
        return EIf(c, th, el).also { it.line = ln }
    }

    private fun whenExpr(ln: Int): E {
        var subject: E? = null
        if (isSym("(")) { i++; subject = expr(); expect(")") }
        expect("{")
        val branches = ArrayList<Pair<List<E>?, List<S>>>()
        while (!atEnd() && !isSym("}")) {
            val before = i
            var conds: ArrayList<E>? = null
            if (isId("else")) { i++ } else {
                conds = ArrayList()
                while (true) {
                    if (isId("in") || isId("is")) { i++; skipType(); conds += ELit(null) } else conds += expr()
                    if (!accept(",")) break
                }
            }
            expect("->")
            branches += conds to blockOrStmt()
            if (i == before) i++
        }
        expect("}")
        return EWhen(subject, branches).also { it.line = ln }
    }
}

// ====================================================================== interpreter

private class Fn(val lam: ELambda, val env: Env)
private class ModList(val mods: List<PMod>)

private class Env(val parent: Env?) {
    private val vars = HashMap<String, Any?>()
    fun has(n: String): Boolean = vars.containsKey(n) || parent?.has(n) == true
    fun get(n: String): Any? = if (vars.containsKey(n)) vars[n] else parent?.get(n)
    fun set(n: String, v: Any?) { vars[n] = v }
}

private val COLORS = mapOf(
    "Red" to 0xFFFF0000L, "Green" to 0xFF00FF00L, "Blue" to 0xFF0000FFL, "Black" to 0xFF000000L, "White" to 0xFFFFFFFFL,
    "Gray" to 0xFF888888L, "DarkGray" to 0xFF444444L, "LightGray" to 0xFFCCCCCCL, "Yellow" to 0xFFFFFF00L,
    "Cyan" to 0xFF00FFFFL, "Magenta" to 0xFFFF00FFL, "Transparent" to 0x00000000L, "Unspecified" to 0x00000000L,
)

private val VALUE_CTORS = setOf(
    "RoundedCornerShape", "CutCornerShape", "PaddingValues", "TextStyle", "BorderStroke", "DpOffset", "Offset", "Size",
    "SpanStyle", "ParagraphStyle", "FontFamily", "TextRange", "Brush", "CornerSize", "WindowInsets", "ButtonColors", "CardColors",
    "Arrangement", "Alignment", "FontWeight", "TextAlign", "ContentScale", "KeyboardOptions", "KeyboardActions", "PaddingValues",
)

private val LISTS = setOf("listOf", "arrayOf", "mutableListOf", "setOf", "persistentListOf", "mutableStateListOf", "emptyList", "intArrayOf")
private val STATE = setOf(
    "mutableStateOf", "mutableIntStateOf", "mutableFloatStateOf", "mutableLongStateOf", "mutableDoubleStateOf",
    "rememberSaveable", "remember", "rememberUpdatedState", "derivedStateOf",
)

private class Engine(val files: Map<String, String>, val strings: Map<String, String>) {
    class Ann(val name: String, val argTokens: List<Tok>, val line: Int)
    class Found(val def: FunDef, val anns: List<Ann>, val params: List<Param>)

    val index = HashMap<String, MutableList<FunDef>>()
    val found = ArrayList<Found>()
    val parseWarnings = LinkedHashSet<String>()

    fun run(): List<PreviewDef> {
        for ((path, src) in files.toSortedMap()) if (path.endsWith(".kt")) scanFile(path, src)
        val out = ArrayList<PreviewDef>()
        for (f in found) {
            val previews = f.anns.filter { it.name == "Preview" || it.name.endsWith(".Preview") }
            if (previews.isEmpty()) continue
            val requiredMissing = f.params.any { it.default == null }
            if (requiredMissing) { out += skipped(f, PreviewMsg.needsParams(f.def.name)); continue }
            val customs = f.anns.filter { it.name != "Composable" && it.name != "Preview" && !it.name.endsWith(".Preview") && !it.name.endsWith(".Composable") }
            for (p in previews) {
                val ip = Interp(this, night(p))
                val roots = ArrayList<PNode>()
                try { ip.inline(f.def, emptyList(), null, Env(null), roots, 0) } catch (e: ParseError) { ip.warn(PreviewMsg.unreadable(e.line)) } catch (e: StackOverflowError) { ip.warn(PreviewMsg.TOO_MANY) }
                customs.take(0)
                val a = annotationValues(p, ip)
                out += PreviewDef(
                    name = f.def.name, file = f.def.file, line = p.line,
                    title = (a["name"] as? PValue.Str)?.s?.takeIf { it.isNotBlank() } ?: f.def.name,
                    widthDp = (a["widthDp"] as? PValue.Num)?.n?.toInt(), heightDp = (a["heightDp"] as? PValue.Num)?.n?.toInt(),
                    showBackground = (a["showBackground"] as? PValue.Bool)?.b ?: false,
                    backgroundColor = (a["backgroundColor"] as? PValue.Num)?.n?.toLong() ?: (a["backgroundColor"] as? PValue.ColorV)?.argb,
                    night = night(p), showSystemUi = (a["showSystemUi"] as? PValue.Bool)?.b ?: false,
                    root = roots, warnings = (parseWarnings + ip.warnings).toList(),
                )
            }
        }
        return out
    }

    private fun skipped(f: Found, msg: String) = PreviewDef(f.def.name, f.def.file, f.def.line, f.def.name, null, null, false, null, false, false, emptyList(), listOf(msg))

    private fun night(p: Ann) = p.argTokens.any { it.s.contains("NIGHT_YES") } || (p.argTokens.any { it.s == "uiMode" } && p.argTokens.any { it.k == K.NUM && it.num == 32.0 })

    private fun annotationValues(a: Ann, ip: Interp): Map<String, Any?> {
        val out = HashMap<String, Any?>()
        try {
            val p = Parser(a.argTokens + Tok(K.EOF, "", a.line, true))
            val args = ArrayList<Arg>()
            while (!p.atEnd()) {
                if (p.peek().k == K.ID && p.isSym("=", 1)) { val nm = p.next().s; p.i++; args += Arg(nm, p.expr()) } else args += Arg(null, p.expr())
                if (!p.accept(",")) break
            }
            for (x in args) if (x.name != null) out[x.name] = ip.eval(x.e, Env(null))
        } catch (_: ParseError) { }
        return out
    }

    private fun scanFile(path: String, src: String) {
        val toks = lex(src)
        val p = Parser(toks)
        var pending = ArrayList<Ann>()
        val modifiers = setOf("private", "internal", "public", "inline", "suspend", "override", "open", "abstract", "final", "tailrec", "operator", "infix", "actual", "expect", "protected", "data", "sealed")
        while (!p.atEnd()) {
            val tk = p.peek()
            if (tk.k == K.SYM && tk.s == "@") {
                p.i++
                var name = ""
                if (p.peek().k == K.ID) { name = p.next().s; while (p.isSym(".") && p.peek(1).k == K.ID) { p.i++; name += "." + p.next().s } }
                if (p.isSym(":")) { p.i++; if (p.peek().k == K.ID) { name = p.next().s } }
                var args: List<Tok> = emptyList()
                if (p.isSym("(") && !p.peek().nl) {
                    val st = p.i
                    p.skipGroup()
                    args = toks.subList(st + 1, maxOf(st + 1, p.i - 1))
                }
                pending += Ann(name.substringAfterLast('.'), args, tk.line)
                continue
            }
            if (tk.k == K.ID && tk.s in modifiers) { p.i++; continue }
            if (tk.k == K.ID && tk.s == "fun") {
                val line = tk.line
                val anns = pending; pending = ArrayList()
                val save = p.i
                try {
                    p.i++
                    if (p.isSym("<")) { var d = 0; while (!p.atEnd()) { val x = p.next(); if (x.k == K.SYM && x.s == "<") d++; if (x.k == K.SYM && x.s == ">") { d--; if (d == 0) break } } }
                    var nameTok = p.next()
                    while (p.isSym(".") && p.peek(1).k == K.ID) { p.i++; nameTok = p.next() } // extension receiver
                    if (nameTok.k != K.ID || !p.isSym("(")) continue
                    val composable = anns.any { it.name == "Composable" }
                    if (!composable) { // skip non-composable functions entirely
                        p.skipGroup()
                        if (p.accept(":")) p.skipType()
                        if (p.isSym("{")) p.skipGroup()
                        continue
                    }
                    val params = params(p)
                    if (p.accept(":")) p.skipType()
                    val body: List<S> = if (p.isSym("{")) p.block() else if (p.accept("=")) listOf(SExpr(p.expr())) else emptyList()
                    val def = FunDef(nameTok.s, path, line, true, params, body)
                    index.getOrPut(def.name) { ArrayList() } += def
                    found += Found(def, anns, params)
                } catch (e: ParseError) {
                    parseWarnings += PreviewMsg.unreadable(e.line)
                    p.i = save + 1
                    while (!p.atEnd() && !(p.isSym("{"))) p.i++
                    if (p.isSym("{")) p.skipGroup()
                }
                continue
            }
            if (tk.k == K.ID && (tk.s == "class" || tk.s == "object" || tk.s == "interface" || tk.s == "val" || tk.s == "var" || tk.s == "package" || tk.s == "import" || tk.s == "typealias")) pending = ArrayList()
            if (tk.k == K.SYM && (tk.s == "{" || tk.s == "}" || tk.s == ";")) pending = ArrayList()
            p.i++
        }
    }

    private fun params(p: Parser): List<Param> {
        p.expect("(")
        val out = ArrayList<Param>()
        while (!p.atEnd() && !p.isSym(")")) {
            val before = p.i
            while (p.peek().k == K.ID && p.peek().s in setOf("vararg", "noinline", "crossinline", "val", "var")) p.i++
            while (p.isSym("@")) { p.i++; if (p.peek().k == K.ID) p.i++; if (p.isSym("(")) p.skipGroup() }
            val nm = p.next()
            var type = ""
            if (p.accept(":")) { val st = p.i; p.skipType(); type = p.t.subList(st, p.i).joinToString(" ") { it.s } }
            var def: E? = null
            if (p.accept("=")) def = p.expr()
            if (nm.k == K.ID) out += Param(nm.s, type, def)
            if (!p.accept(",")) break
            if (p.i == before) p.i++
        }
        p.expect(")")
        return out
    }
}

private class Interp(val eng: Engine, val night: Boolean) {
    val warnings = LinkedHashSet<String>()
    private var nodes = 0
    private val active = ArrayList<String>()
    private var cut = false

    fun warn(m: String) { warnings += m }

    // ---- value evaluation

    private fun truthy(v: Any?): Boolean? = when (v) {
        is PValue.Bool -> v.b
        is PValue.Num -> v.n != 0.0
        else -> null
    }

    private fun num(v: Any?): Double? = (v as? PValue.Num)?.n

    private fun text(v: Any?): String? = when (v) {
        is PValue.Str -> v.s
        is PValue.Num -> if (v.unit == "hex") null else if (v.n == Math.floor(v.n) && !v.n.isInfinite() && v.unit != "f") v.n.toLong().toString() else v.n.toString().removeSuffix(".0").let { if (v.unit == "f" && v.n == Math.floor(v.n)) v.n.toString() else it }
        is PValue.Bool -> v.b.toString()
        else -> null
    }

    fun eval(e: E, env: Env): Any? = when (e) {
        is ELit -> e.v
        is EStr -> PValue.Str(expandTemplate(e.raw, e.isRaw, env))
        is EName -> evalName(e.name, env)
        is EMember -> evalMember(e, env)
        is ECall -> evalCall(e, env)
        is EBin -> evalBin(e, env)
        is EUn -> {
            val v = eval(e.e, env)
            when (e.op) {
                "!" -> truthy(v)?.let { PValue.Bool(!it) }
                "-" -> (v as? PValue.Num)?.let { PValue.Num(-it.n, it.unit) } ?: v
                else -> v
            }
        }
        is EIf -> {
            val c = truthy(eval(e.cond, env))
            if (c == null) warn(PreviewMsg.notConstant(e.line))
            val branch = if (c != false) e.then else e.els
            blockValue(branch ?: emptyList(), Env(env))
        }
        is EWhen -> blockValue(pickWhen(e, env) ?: emptyList(), Env(env))
        is ELambda -> Fn(e, env)
        is EIndex -> {
            val t = eval(e.target, env); val ix = num(eval(e.index, env))?.toInt()
            if (t is PValue.Lst && ix != null) t.items.getOrNull(ix) else PValue.Ref("item")
        }
    }

    private fun blockValue(b: List<S>, env: Env): Any? {
        var last: Any? = null
        for (s in b) when (s) {
            is SVal -> env.set(s.name, s.e?.let { eval(it, env) })
            is SExpr -> last = eval(s.e, env)
            is SFor -> {}
        }
        return last
    }

    private fun pickWhen(e: EWhen, env: Env): List<S>? {
        val subj = e.subject?.let { eval(it, env) }
        var decided = false
        for ((conds, body) in e.branches) {
            if (conds == null) return body
            for (c in conds) {
                val v = eval(c, env)
                val hit = if (e.subject != null) { if (subj == null || subj is PValue.Ref || v == null || v is PValue.Ref) null else subj == v } else truthy(v)
                if (hit == true) return body
                if (hit == null) decided = true
            }
        }
        if (decided) { warn(PreviewMsg.notConstant(e.line)); return e.branches.firstOrNull { it.first != null }?.second }
        return null
    }

    private fun evalName(n: String, env: Env): Any? {
        if (env.has(n)) return env.get(n)
        return when (n) {
            "Modifier" -> ModList(emptyList())
            else -> PValue.Ref(n)
        }
    }

    private fun evalMember(e: EMember, env: Env): Any? {
        val t = eval(e.target, env)
        return member(t, e.name)
    }

    private fun member(t: Any?, name: String): Any? = when {
        t is ModList -> ModList(t.mods + PMod(name, emptyList(), emptyMap()))
        t is PValue.Num && (name == "dp" || name == "sp") -> PValue.Num(t.n, name)
        t is PValue.Num && name in setOf("toFloat", "toInt", "toDouble", "toLong") -> t
        t is PValue.Num && name == "em" -> PValue.Num(t.n, "sp")
        t is PValue.Ref && name == "value" -> t
        t is PValue.Ref && t.name == "Color" -> COLORS[name]?.let { PValue.ColorV(it) } ?: PValue.Ref("Color.$name")
        t is PValue.Ref -> PValue.Ref(t.name + "." + name)
        t is PValue.Lst && name == "size" -> PValue.Num(t.items.size.toDouble(), "")
        t is PValue.Str && name == "length" -> PValue.Num(t.s.length.toDouble(), "")
        t is PValue.Lst && name == "lastIndex" -> PValue.Num((t.items.size - 1).toDouble(), "")
        t is PValue.Str && name in setOf("value") -> t
        t == null -> null
        else -> t
    }

    private fun evalBin(e: EBin, env: Env): Any? {
        if (e.op == "&&" || e.op == "||") {
            val a = truthy(eval(e.l, env)); val b = truthy(eval(e.r, env))
            return if (a == null || b == null) null else PValue.Bool(if (e.op == "&&") a && b else a || b)
        }
        val a = eval(e.l, env); val b = eval(e.r, env)
        return when (e.op) {
            "+" -> when {
                a is PValue.Num && b is PValue.Num -> PValue.Num(a.n + b.n, if (a.unit == b.unit) a.unit else a.unit.ifEmpty { b.unit })
                a is PValue.Str || b is PValue.Str -> {
                    val x = text(a); val y = text(b)
                    if (x != null && y != null) PValue.Str(x + y) else PValue.Ref("text")
                }
                else -> PValue.Ref("expr")
            }
            "-", "*", "/", "%" -> if (a is PValue.Num && b is PValue.Num) PValue.Num(when (e.op) { "-" -> a.n - b.n; "*" -> a.n * b.n; "/" -> if (b.n == 0.0) 0.0 else a.n / b.n; else -> if (b.n == 0.0) 0.0 else a.n % b.n }, a.unit.ifEmpty { b.unit }) else PValue.Ref("expr")
            "==", "!=" -> if (a == null || b == null || a is PValue.Ref || b is PValue.Ref) (if (a == null && b == null) PValue.Bool(e.op == "==") else null) else {
                val eq = if (a is PValue.Num && b is PValue.Num) a.n == b.n else a == b
                PValue.Bool(if (e.op == "==") eq else !eq)
            }
            "<", ">", "<=", ">=" -> if (a is PValue.Num && b is PValue.Num) PValue.Bool(when (e.op) { "<" -> a.n < b.n; ">" -> a.n > b.n; "<=" -> a.n <= b.n; else -> a.n >= b.n }) else null
            ".." , "until" -> if (a is PValue.Num && b is PValue.Num) PValue.Lst(((a.n.toInt())..(if (e.op == "until") b.n.toInt() - 1 else b.n.toInt())).take(50).map { PValue.Num(it.toDouble(), "") }) else PValue.Lst(emptyList())
            "?:" -> if (a == null) b else a
            "or", "and", "xor", "shl", "shr", "to", "in", "downTo", "step" -> if (e.op == "or" || e.op == "and") a else PValue.Ref("expr")
            else -> PValue.Ref("expr")
        }
    }

    private fun expandTemplate(raw: String, isRaw: Boolean, env: Env): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && !isRaw && i + 1 < raw.length) {
                when (val d = raw[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 } 't' -> { sb.append('\t'); i += 2 }
                    'u' -> { val hex = raw.substring(i + 2, minOf(raw.length, i + 6)); val cp = hex.toIntOrNull(16); if (cp != null && hex.length == 4) { sb.append(cp.toChar()); i += 6 } else { sb.append('u'); i += 2 } }
                    else -> { sb.append(d); i += 2 }
                }
                continue
            }
            if (c == '$' && i + 1 < raw.length) {
                if (raw[i + 1] == '{') {
                    var depth = 1; var j = i + 2
                    while (j < raw.length && depth > 0) { if (raw[j] == '{') depth++ else if (raw[j] == '}') depth--; j++ }
                    val inner = raw.substring(i + 2, maxOf(i + 2, j - 1))
                    val v = try { Parser(lex(inner)).expr().let { eval(it, env) } } catch (_: ParseError) { null }
                    sb.append(text(v) ?: "\${$inner}")
                    i = j; continue
                }
                if (raw[i + 1].isLetter() || raw[i + 1] == '_') {
                    var j = i + 1
                    while (j < raw.length && (raw[j].isLetterOrDigit() || raw[j] == '_')) j++
                    val name = raw.substring(i + 1, j)
                    val v = if (env.has(name)) env.get(name) else null
                    sb.append(text(v) ?: "\$$name")
                    i = j; continue
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    private fun argValue(a: Arg, env: Env): Any? = eval(a.e, env)

    private fun toP(v: Any?): PValue? = when (v) {
        is PValue -> v
        is List<*> -> PValue.Lst(v.mapNotNull { toP(it) })
        else -> null
    }

    private fun evalCall(c: ECall, env: Env): Any? {
        val callee = c.callee
        if (callee is EName) {
            val n = callee.name
            if (env.has(n) && env.get(n) is Fn) return null
            when {
                n == "Color" -> {
                    val a = c.args.firstOrNull()?.let { eval(it.e, env) }
                    return if (a is PValue.Num) PValue.ColorV(argbOf(a)) else PValue.ColorV(0xFF808080L)
                }
                n in LISTS -> return PValue.Lst(c.args.mapNotNull { toP(eval(it.e, env)) })
                n == "stringResource" || n == "pluralStringResource" -> {
                    val a = c.args.firstOrNull()?.let { eval(it.e, env) }
                    val key = (a as? PValue.Ref)?.name?.substringAfterLast('.')
                    if (key == null) return PValue.Ref("string")
                    return eng.strings[key]?.let { PValue.Str(formatArgs(it, c.args.drop(1).map { x -> eval(x.e, env) })) } ?: run { warn(PreviewMsg.missingString(key)); PValue.Str(key) }
                }
                n in STATE -> {
                    val lam = c.lambda
                    if (lam != null) return blockValue(lam.body, Env(env))
                    return c.args.firstOrNull()?.let { eval(it.e, env) } ?: PValue.Ref(n)
                }
                n == "isSystemInDarkTheme" -> return PValue.Bool(night)
                n in VALUE_CTORS -> return PValue.PCall(n, c.args.filter { it.name == null }.mapNotNull { toP(eval(it.e, env)) }, c.args.filter { it.name != null }.mapNotNull { a -> toP(eval(a.e, env))?.let { a.name!! to it } }.toMap())
                n == "Modifier" -> return ModList(emptyList())
            }
            return PValue.Ref("$n()")
        }
        if (callee is EMember) {
            val recv = eval(callee.target, env)
            val name = callee.name
            val pos = c.args.filter { it.name == null }.map { eval(it.e, env) }
            val named = c.args.filter { it.name != null }.associate { it.name!! to eval(it.e, env) }
            when {
                recv is ModList -> {
                    if (name == "then") { val m = pos.firstOrNull(); return ModList(recv.mods + ((m as? ModList)?.mods ?: emptyList())) }
                    return ModList(recv.mods + PMod(name, pos.mapNotNull { toP(it) }, named.mapNotNull { (k, v) -> toP(v)?.let { k to it } }.toMap()))
                }
                recv is PValue.Ref && recv.name == "Modifier" -> return ModList(listOf(PMod(name, pos.mapNotNull { toP(it) }, named.mapNotNull { (k, v) -> toP(v)?.let { k to it } }.toMap())))
                recv is PValue.ColorV && name == "copy" -> {
                    val alpha = num(named["alpha"])
                    return if (alpha != null) PValue.ColorV(((Math.round(alpha.coerceIn(0.0, 1.0) * 255)) shl 24) or (recv.argb and 0xFFFFFF)) else recv
                }
                recv is PValue.Ref -> return PValue.PCall(recv.name + "." + name, pos.mapNotNull { toP(it) }, named.mapNotNull { (k, v) -> toP(v)?.let { k to it } }.toMap())
                recv is PValue.Str -> return when (name) {
                    "uppercase", "uppercase()" -> PValue.Str(recv.s.uppercase()); "lowercase" -> PValue.Str(recv.s.lowercase()); "trim" -> PValue.Str(recv.s.trim())
                    "repeat" -> PValue.Str(recv.s.repeat((num(pos.firstOrNull()) ?: 1.0).toInt().coerceIn(0, 20)))
                    "format" -> PValue.Str(formatArgs(recv.s, pos))
                    else -> recv
                }
                recv is PValue.Lst -> return when (name) {
                    "first" -> recv.items.firstOrNull(); "last" -> recv.items.lastOrNull(); "size" -> PValue.Num(recv.items.size.toDouble(), "")
                    else -> recv
                }
                recv is PValue.Num && name in setOf("coerceAtLeast", "coerceAtMost", "coerceIn", "toFloat", "toInt", "dp", "sp") -> return recv
                else -> return recv
            }
        }
        return null
    }

    private fun formatArgs(fmt: String, args: List<Any?>): String {
        if (args.isEmpty()) return fmt
        var idx = 0
        return Regex("%(\\d+\\$)?[sdf]|%\\.\\d+f").replace(fmt) { text(args.getOrNull(idx++)) ?: it.value }
    }

    private fun argbOf(a: PValue.Num): Long {
        val v = a.n.toLong()
        return if (a.unit == "hex" && v <= 0xFFFFFFL) 0xFF000000L or v else v and 0xFFFFFFFFL
    }

    // ---- emission of composables

    fun inline(def: FunDef, args: List<Arg>, trailing: ELambda?, callerEnv: Env, out: MutableList<PNode>, depth: Int, evalEnv: Env = callerEnv) {
        if (depth > 8) return
        if (def.name in active) { warn(PreviewMsg.recursive(def.name)); return }
        active += def.name
        try {
            val env = Env(null)
            val positional = args.filter { it.name == null }
            var pi = 0
            for ((idx, p) in def.params.withIndex()) {
                val named = args.firstOrNull { it.name == p.name }
                val isFnType = p.type.contains("->")
                val value: Any? = when {
                    named != null -> if (named.e is ELambda) Fn(named.e, evalEnv) else eval(named.e, evalEnv)
                    pi < positional.size && !(isFnType && trailing != null && idx == def.params.lastIndex && positional.size <= pi) -> {
                        val a = positional[pi++]; if (a.e is ELambda) Fn(a.e, evalEnv) else eval(a.e, evalEnv)
                    }
                    isFnType && trailing != null && def.params.drop(idx + 1).none { it.type.contains("->") && args.none { x -> x.name == it.name } } -> Fn(trailing, evalEnv)
                    p.default != null -> eval(p.default, env)
                    isFnType -> null
                    else -> PValue.Ref(p.name)
                }
                env.set(p.name, value)
            }
            emitBlock(def.body, env, out, depth + 1)
        } finally { active.removeAt(active.lastIndex) }
    }

    private fun emitBlock(b: List<S>, env: Env, out: MutableList<PNode>, depth: Int) {
        val local = Env(env)
        for (s in b) emitStmt(s, local, out, depth)
    }

    private fun emitStmt(s: S, env: Env, out: MutableList<PNode>, depth: Int) {
        when (s) {
            is SVal -> env.set(s.name, s.e?.let { eval(it, env) })
            is SExpr -> emitExpr(s.e, env, out, depth)
            is SFor -> {
                val it = eval(s.iter, env)
                val items = (it as? PValue.Lst)?.items ?: listOf(PValue.Ref(s.v))
                for (x in items.take(20)) { val e2 = Env(env); e2.set(s.v, x); emitBlock(s.body, e2, out, depth) }
            }
        }
    }

    private fun emitExpr(e: E, env: Env, out: MutableList<PNode>, depth: Int) {
        if (cut) return
        when (e) {
            is EIf -> {
                val c = truthy(eval(e.cond, env))
                if (c == null) warn(PreviewMsg.notConstant(e.line))
                val branch = if (c != false) e.then else e.els
                if (branch != null) emitBlock(branch, env, out, depth)
            }
            is EWhen -> pickWhen(e, env)?.let { emitBlock(it, env, out, depth) }
            is ECall -> emitCall(e, env, out, depth)
            else -> {}
        }
    }

    private fun emitLambda(l: ELambda, env: Env, out: MutableList<PNode>, depth: Int, bind: List<Any?> = emptyList()) {
        val e2 = Env(env)
        l.params.forEachIndexed { i, p -> e2.set(p, if (i < bind.size) bind[i] else PValue.Ref(p)) }
        if (l.params.isEmpty() && bind.isNotEmpty()) e2.set("it", bind[0])
        emitBlock(l.body, e2, out, depth)
    }

    private fun emitCall(c: ECall, env: Env, out: MutableList<PNode>, depth: Int) {
        val callee = c.callee
        val name = when (callee) { is EName -> callee.name; is EMember -> callee.name; else -> return }
        val line = c.line

        // slot invocation: content()
        if (callee is EName) {
            val bound = if (env.has(name)) env.get(name) else null
            if (bound is Fn) { emitLambda(bound.lam, bound.env, out, depth, c.args.map { eval(it.e, env) }); return }
            if (env.has(name)) return
        }

        // loops over literal data
        if (callee is EMember && (name == "forEach" || name == "forEachIndexed") && c.lambda != null) {
            val recv = eval(callee.target, env)
            val items = (recv as? PValue.Lst)?.items ?: return
            items.take(20).forEachIndexed { i, x -> emitLambda(c.lambda, env, out, depth, if (name == "forEachIndexed") listOf(PValue.Num(i.toDouble(), ""), x) else listOf(x)) }
            return
        }
        if (callee is EName && name == "repeat" && c.lambda != null) {
            val n = (num(c.args.firstOrNull()?.let { eval(it.e, env) }) ?: 1.0).toInt().coerceIn(0, 20)
            for (i in 0 until n) emitLambda(c.lambda, env, out, depth, listOf(PValue.Num(i.toDouble(), "")))
            return
        }
        if (callee is EName && (name == "items" || name == "itemsIndexed") && c.lambda != null) {
            val first = c.args.firstOrNull { it.name == null || it.name == "items" || it.name == "count" }?.let { eval(it.e, env) }
            val list: List<Any?> = when {
                first is PValue.Lst -> first.items.take(20)
                first is PValue.Num -> (0 until first.n.toInt().coerceIn(0, 20)).map { PValue.Num(it.toDouble(), "") }
                else -> { warn(PreviewMsg.sampleData(line)); listOf(PValue.Ref("item"), PValue.Ref("item"), PValue.Ref("item")) }
            }
            list.forEachIndexed { i, x -> emitLambda(c.lambda, env, out, depth, if (name == "itemsIndexed") listOf(PValue.Num(i.toDouble(), ""), x) else listOf(x)) }
            return
        }
        if (callee is EName && name == "item" && c.lambda != null) { emitLambda(c.lambda, env, out, depth); return }
        if (callee is EName && (name == "key" || name == "CompositionLocalProvider" || name == "SideEffect" && false) && c.lambda != null) { emitLambda(c.lambda, env, out, depth); return }

        if (callee !is EName) return

        // project composables are inlined; themes without a project definition are transparent
        val defs = eng.index[name]
        if (defs != null) {
            val def = defs.firstOrNull { d -> c.args.filter { it.name != null }.all { a -> d.params.any { it.name == a.name } } } ?: defs.first()
            inline(def, c.args, c.lambda, env, out, depth, env)
            return
        }
        if (name.endsWith("Theme") || name == "MaterialTheme") {
            val fn: Fn? = c.lambda?.let { Fn(it, env) } ?: c.args.firstOrNull { it.name == "content" }?.let { a -> if (a.e is ELambda) Fn(a.e, env) else eval(a.e, env) as? Fn }
            if (fn != null) { emitLambda(fn.lam, fn.env, out, depth); return }
        }
        if (name[0].isUpperCase() && name !in VALUE_CTORS) out += buildNode(name, c, env, depth)
    }

    private fun buildNode(name: String, c: ECall, env: Env, depth: Int): PNode {
        if (++nodes > 2000) { if (!cut) { cut = true; warn(PreviewMsg.TOO_MANY) } ; return PNode(name, c.line, emptyList(), emptyMap(), emptyList(), emptyList(), emptyMap(), false) }
        val positional = ArrayList<PValue>()
        val named = LinkedHashMap<String, PValue>()
        val slots = LinkedHashMap<String, List<PNode>>()
        var mods: List<PMod> = emptyList()
        for (a in c.args) {
            if (a.e is ELambda) {
                val nm = a.name
                if (nm == null || nm.startsWith("on") && nm.length > 2 && nm[2].isUpperCase() || nm == "key" || nm == "contentType") continue
                val sl = ArrayList<PNode>(); emitLambda(a.e, env, sl, depth); slots[nm] = sl
                continue
            }
            val v = eval(a.e, env)
            if (a.name == "modifier") { mods = (v as? ModList)?.mods ?: emptyList(); continue }
            val p = toP(v) ?: continue
            if (a.name == null) positional += p else named[a.name] = p
        }
        val children = ArrayList<PNode>()
        c.lambda?.let { emitLambda(it, env, children, depth) }
        val known = name in KNOWN
        if (!known) warn(PreviewMsg.cannotPreview(name))
        return PNode(name, c.line, positional, named, mods, children, slots, placeholder = !known)
    }

    companion object {
        val KNOWN = setOf(
            "Column", "Row", "Box", "Text", "Button", "OutlinedButton", "TextButton", "FilledTonalButton", "ElevatedButton", "IconButton",
            "FloatingActionButton", "SmallFloatingActionButton", "LargeFloatingActionButton", "ExtendedFloatingActionButton",
            "Card", "ElevatedCard", "OutlinedCard", "Surface", "Scaffold", "TopAppBar", "CenterAlignedTopAppBar", "LargeTopAppBar", "MediumTopAppBar",
            "NavigationBar", "NavigationBarItem", "BottomAppBar", "Spacer", "Divider", "HorizontalDivider", "VerticalDivider", "Icon", "Image", "AsyncImage",
            "TextField", "OutlinedTextField", "Switch", "Checkbox", "RadioButton", "Slider", "LinearProgressIndicator", "CircularProgressIndicator",
            "LazyColumn", "LazyRow", "LazyVerticalGrid", "ListItem", "FilterChip", "AssistChip", "SuggestionChip", "Badge",
            "BoxWithConstraints", "ConstraintLayout", "FlowRow", "FlowColumn", "AlertDialog", "ModalBottomSheet", "Tab", "TabRow", "NavigationRail",
        )
    }
}
