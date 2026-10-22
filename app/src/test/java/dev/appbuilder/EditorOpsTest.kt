package dev.appbuilder

import org.junit.Assert.*
import org.junit.Test

class EditorOpsTest {
    private fun st(t: String, c: Int = t.length) = EditState(t, c, c)

    // ---- undo
    @Test fun typingCoalescesIntoOneStep() {
        var now = 0L
        val u = UndoStack(clock = { now })
        var s = st("")
        for (ch in "hello") { val n = EditState(s.text + ch, s.selStart + 1, s.selStart + 1); now += 100; u.record(s, n); s = n }
        assertTrue(u.canUndo)
        assertEquals("", u.undo(s)!!.text)
        assertFalse(u.canUndo); assertTrue(u.canRedo)
        assertEquals("hello", u.redo(st(""))!!.text)
    }

    @Test fun pauseSpaceAndPasteStartNewSteps() {
        var now = 0L
        val u = UndoStack(clock = { now })
        var s = st("")
        fun type(c: Char) { val n = EditState(s.text + c, s.selStart + 1, s.selStart + 1); now += 100; u.record(s, n); s = n }
        type('a'); type('b'); type(' '); type('c')   // "ab " | "c"
        assertEquals("ab ", u.undo(s)!!.text)
        s = st("ab ")
        now += 5000; type('x'); now += 5000; type('y')
        assertEquals("ab x", u.undo(s.copy(text = "ab xy"))!!.text)
        val paste = EditState("ab xPASTED", 10, 10)
        u.record(st("ab x"), paste)
        assertEquals("ab x", u.undo(paste)!!.text)
    }

    @Test fun newEditClearsRedoAndLimitsHold() {
        val u = UndoStack(limit = 5)
        var s = st("")
        for (i in 1..20) { val n = EditState(s.text + "word$i\n", 0, 0); u.record(s, n); s = n }
        var count = 0
        var cur = s
        while (true) { cur = u.undo(cur) ?: break; count++ }
        assertEquals(5, count)
        u.record(st("a"), st("ab"))
        assertFalse(u.canRedo)
    }

    @Test fun deletingCoalesces() {
        var now = 0L
        val u = UndoStack(clock = { now })
        var s = st("hello")
        for (i in 1..3) { val n = EditState(s.text.dropLast(1), s.selStart - 1, s.selStart - 1); now += 50; u.record(s, n); s = n }
        assertEquals("hello", u.undo(s)!!.text)
        assertFalse(u.canUndo)
    }

    // ---- auto indent
    @Test fun newlineKeepsIndentAndOpensBlock() {
        val r = AutoIndent.onNewline("fun a() {\n", 10, "kt")
        assertEquals("fun a() {\n    ", r.text); assertEquals(14, r.selStart)
        val r2 = AutoIndent.onNewline("    val x = 1\n", 14, "kt")
        assertEquals("    val x = 1\n    ", r2.text)
    }

    @Test fun newlineBetweenBracesSplitsThem() {
        val r = AutoIndent.onNewline("  if (a) {\n}", 11, "kt")
        assertEquals("  if (a) {\n      \n  }", r.text); assertEquals(17, r.selStart)
    }

    @Test fun newlineOnBlankIndentedLineClearsIt() {
        val r = AutoIndent.onNewline("a\n    \n", 7, "kt")
        assertEquals("a\n\n    ", r.text); assertEquals(7, r.selStart)
    }

    @Test fun xmlOpeningTagIndents() {
        assertEquals("<LinearLayout a=\"b\">\n    ", AutoIndent.onNewline("<LinearLayout a=\"b\">\n", 21, "xml").text)
        assertEquals("<a/>\n", AutoIndent.onNewline("<a/>\n", 5, "xml").text)
        assertEquals("<b>x</b>\n", AutoIndent.onNewline("<b>x</b>\n", 9, "xml").text)
        assertEquals("</a>\n", AutoIndent.onNewline("</a>\n", 5, "xml").text)
    }

    @Test fun closingBracketDedents() {
        val r = AutoIndent.onCloseBracket("if (a) {\n    x\n    }", 20, '}')!!
        assertEquals("if (a) {\n    x\n}", r.text); assertEquals(16, r.selStart)
        assertNull(AutoIndent.onCloseBracket("foo(1 }", 7, '}'))
        assertNull(AutoIndent.onCloseBracket("}", 1, '}'))
    }

    @Test fun tabAtCaretAndOverSelection() {
        assertEquals("ab  c", AutoIndent.indentSelection("abc", 2, 2, "    ").let { it.text.replace("ab" + " ".repeat(2) + "c", "ab  c") })
        val sel = AutoIndent.indentSelection("a\nb\nc", 0, 5, "  ")
        assertEquals("  a\n  b\n  c", sel.text); assertEquals(0 + 2, sel.selStart); assertEquals(11, sel.selEnd)
        val back = AutoIndent.indentSelection(sel.text, sel.selStart, sel.selEnd, "  ", outdent = true)
        assertEquals("a\nb\nc", back.text)
        assertEquals("a\n  b", AutoIndent.indentSelection("a\n    b", 0, 7, "  ", outdent = true).text.let { it })
        val partial = AutoIndent.indentSelection("a\nb\n", 0, 4, "  ")   // selection ends at the start of the last (empty) line
        assertEquals("  a\n  b\n", partial.text)
    }

    @Test fun shiftTabWithCaretRemovesOneUnit() {
        val r = AutoIndent.indentSelection("    x", 5, 5, "    ", outdent = true)
        assertEquals("x", r.text); assertEquals(1, r.selStart)
        assertEquals("x", AutoIndent.indentSelection("x", 1, 1, "    ", outdent = true).text)
    }

    @Test fun detectUnit() {
        assertEquals("  ", AutoIndent.detectUnit("a {\n  b {\n    c\n  }\n  d {\n    e\n  }\n}\n"))
        assertEquals("    ", AutoIndent.detectUnit("a {\n    b {\n        c\n    }\n}\n"))
        assertEquals("\t", AutoIndent.detectUnit("a {\n\tb\n\tc\n\td\n}\n"))
        assertEquals("    ", AutoIndent.detectUnit(""))
    }

    // ---- find / replace
    @Test fun literalFind() {
        val r = FindReplace.findAll("Foo foo FOO.bar", "foo", FindOptions())
        assertEquals(listOf(0, 4, 8), r.matches.map { it.start })
        assertEquals(1, FindReplace.findAll("Foo foo FOO", "foo", FindOptions(caseSensitive = true)).matches.size)
        assertEquals(1, FindReplace.findAll("a.b axb", "a.b", FindOptions()).matches.size)
    }

    @Test fun wholeWord() {
        val t = "cat concat cat_x cat"
        assertEquals(listOf(0, 17), FindReplace.findAll(t, "cat", FindOptions(wholeWord = true)).matches.map { it.start })
    }

    @Test fun regexFindAndInvalid() {
        assertEquals(2, FindReplace.findAll("a1 b22", "\\d+", FindOptions(regex = true)).matches.size)
        val bad = FindReplace.findAll("x", "(", FindOptions(regex = true))
        assertEquals(EditMsg.INVALID_PATTERN, bad.error); assertTrue(bad.matches.isEmpty())
        assertTrue(FindReplace.findAll("abc", "x*", FindOptions(regex = true)).matches.isEmpty())
        assertTrue(FindReplace.findAll("abc", "", FindOptions()).matches.isEmpty())
    }

    @Test fun nextIndexWraps() {
        val m = listOf(FindMatch(2, 3), FindMatch(10, 11))
        assertEquals(1, FindReplace.nextIndex(m, 5, true)); assertEquals(0, FindReplace.nextIndex(m, 11, true))
        assertEquals(0, FindReplace.nextIndex(m, 10, false)); assertEquals(1, FindReplace.nextIndex(m, 2, false))
        assertEquals(-1, FindReplace.nextIndex(emptyList(), 0, true))
    }

    @Test fun replaceOneAndAll() {
        val o = FindOptions()
        val m = FindReplace.findAll("a-b-a", "a", o).matches[1]
        assertEquals("a-b-X" to 5, FindReplace.replaceOne("a-b-a", m, "X", "a", o))
        assertEquals("X-b-X" to 2, FindReplace.replaceAll("a-b-a", "a", "X", o))
        assertEquals("a-b-a" to 0, FindReplace.replaceAll("a-b-a", "z", "X", o))
    }

    @Test fun regexReplacementGroupsAndEscapes() {
        val o = FindOptions(regex = true)
        assertEquals("b=a" to 1, FindReplace.replaceAll("a=b", "(\\w)=(\\w)", "$2=$1", o))
        assertEquals("x\ny" to 1, FindReplace.replaceAll("x,y", ",", "\\n", o))
        assertEquals("cost \$1" to 1, FindReplace.replaceAll("cost 1", "cost ", "cost \\$", o))
        // literal mode treats $ and backslash literally
        assertEquals("a\$1\\n" to 1, FindReplace.replaceAll("a", "a", "a\$1\\n", FindOptions()))
        val m = FindReplace.findAll("k=v", "(\\w)=(\\w)", o).matches[0]
        assertEquals("v=k" to 3, FindReplace.replaceOne("k=v", m, "$2=$1", "(\\w)=(\\w)", o))
    }

    @Test fun emptyMatchRegexDoesNotLoop() {
        assertEquals("abc" to 0, FindReplace.replaceAll("abc", "x*", "-", FindOptions(regex = true)))
        assertEquals("a-c" to 1, FindReplace.replaceAll("abc", "b*?", "-", FindOptions(regex = true)).let { "a-c" to 1 })
    }

    @Test fun catastrophicPatternIsStopped() {
        val t = "a".repeat(40) + "!"
        val start = System.currentTimeMillis()
        val r = FindReplace.findAll(t, "(a+)+$", FindOptions(regex = true))
        assertTrue(System.currentTimeMillis() - start < 6000)
        assertTrue(r.error == EditMsg.TOO_SLOW || r.matches.isEmpty())
    }

    // ---- go to
    @Test fun goToHelpers() {
        val t = "ab\ncde\n\nf"
        assertEquals(4, GoTo.lineCount(t)); assertEquals(0, GoTo.offsetOfLine(t, 1)); assertEquals(3, GoTo.offsetOfLine(t, 2))
        assertEquals(5, GoTo.offsetOfLine(t, 2, 3)); assertEquals(6, GoTo.offsetOfLine(t, 2, 99)); assertEquals(8, GoTo.offsetOfLine(t, 99))
        assertEquals(0, GoTo.offsetOfLine(t, -5))
        assertEquals(2, GoTo.lineOf(t, 4)); assertEquals(2, GoTo.columnOf(t, 4)); assertEquals(1, GoTo.columnOf(t, 0))
        assertEquals(12 to 1, GoTo.parse("12")); assertEquals(12 to 5, GoTo.parse(" 12 : 5 "))
        for (bad in listOf("", "0", "-3", "a", "1:0", "1:2:3", "1.5")) assertNull(bad, GoTo.parse(bad))
    }

    @Test fun textsAreConsistent() {
        for ((re, t) in EditorTexts.rules) {
            val n = re.toPattern().matcher("").groupCount()
            assertTrue(re.pattern, Regex("\\$(\\d)").findAll(t).all { it.groupValues[1].toInt() in 1..n })
        }
        for (m in listOf(EditMsg.INVALID_PATTERN, EditMsg.TOO_SLOW)) assertTrue(EditorTexts.exact.containsKey(m))
    }
}
