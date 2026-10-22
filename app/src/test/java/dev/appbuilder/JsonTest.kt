package dev.appbuilder

import org.junit.Assert.*
import org.junit.Test

class JsonTest {
    @Test fun parsesAllTypes() {
        val v = Json.parse("""{"a":1,"b":2.5,"c":"x","d":[true,false,null],"e":{"f":"g"},"n":-3e2}""")
        assertEquals(1L, Json.long(v, "a")); assertEquals(2.5, (v as Map<*, *>)["b"])
        assertEquals("x", Json.str(v, "c")); assertEquals(listOf(true, false, null), Json.arr(v, "d"))
        assertEquals("g", Json.str(Json.obj(v, "e"), "f")); assertEquals(-300.0, v["n"])
    }

    @Test fun stringEscapesAndUnicode() {
        assertEquals("a\"b\\c\n\t\u00e9\ud83d\ude00", Json.parse("\"a\\\"b\\\\c\\n\\t\\u00e9\\ud83d\\ude00\""))
        val s = Json.stringify(mapOf("k" to "line1\nline2 \"q\" \u0001 é 😀"))
        assertEquals("line1\nline2 \"q\" \u0001 é 😀", Json.str(Json.parse(s), "k"))
    }

    @Test fun roundTripPretty() {
        val src = mapOf("a" to listOf(1, 2, mapOf("b" to null)), "c" to true, "d" to "x")
        val back = Json.parse(Json.stringify(src, pretty = true))
        assertEquals(Json.stringify(src), Json.stringify(back))
    }

    @Test fun errorsDoNotCrash() {
        for (bad in listOf("", "{", "[1,", "{\"a\":}", "tru", "\"abc", "{\"a\" 1}", "[1 2]", "01x", "{} extra", "\"\\ud83d\"x\\u12\"")) {
            try { Json.parse(bad); if (bad != "\"\\ud83d\"x\\u12\"") fail("accepted: $bad") } catch (e: JsonException) { assertTrue(e.message!!.isNotEmpty()) }
        }
    }

    @Test fun deepNestingIsRejectedNotOverflowed() {
        val deep = "[".repeat(5000) + "]".repeat(5000)
        try { Json.parse(deep); fail() } catch (e: JsonException) {}
    }

    @Test fun typedAccessReturnsNullOnMismatch() {
        val v = Json.parse("""{"a":"x"}""")
        assertNull(Json.long(v, "a")); assertNull(Json.str(v, "zz")); assertNull(Json.str(null, "a"))
    }
}
