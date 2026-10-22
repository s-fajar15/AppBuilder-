package dev.appbuilder

import org.junit.Assert.*
import org.junit.Test

class ComposePreviewTest {
    private val template = """
package com.example.app

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MyAppTheme { Greeting("Android") } }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello ${'$'}name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MyAppTheme {
        Greeting("Android")
    }
}
"""
    private val theme = """
@Composable
fun MyAppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(), typography = Typography, content = content)
}
"""

    private fun previews(vararg f: Pair<String, String>, strings: Map<String, String> = emptyMap()) =
        ComposePreviewParser.findPreviews(f.toMap(), strings)

    private fun flat(n: List<PNode>): List<PNode> = n.flatMap { listOf(it) + flat(it.children) + it.slots.values.flatMap { s -> flat(s) } }

    @Test fun defaultTemplate() {
        val p = previews("Main.kt" to template, "Theme.kt" to theme).single()
        assertEquals("GreetingPreview", p.name); assertTrue(p.showBackground)
        val text = p.root.single()
        assertEquals("Text", text.name)
        assertEquals(PValue.Str("Hello Android!"), text.args["text"])
        assertTrue(p.warnings.toString(), p.warnings.isEmpty())
    }

    @Test fun loginScreen() {
        val src = """
@Composable
fun LoginScreen() {
    var email by remember { mutableStateOf("ann@x.com") }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Welcome back", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(onClick = { }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Sign in") }
    }
}
@Preview @Composable fun LoginPreview() { LoginScreen() }
"""
        val p = previews("L.kt" to src).single()
        val col = p.root.single()
        assertEquals("Column", col.name)
        assertEquals(listOf("fillMaxSize", "padding"), col.modifier.map { it.name })
        assertEquals(PValue.Num(24.0, "dp"), col.modifier[1].positional[0])
        assertEquals(PValue.PCall("Arrangement.spacedBy", listOf(PValue.Num(12.0, "dp")), emptyMap()), col.args["verticalArrangement"])
        assertEquals(listOf("Text", "OutlinedTextField", "Spacer", "Button"), col.children.map { it.name })
        val field = col.children[1]
        assertEquals(PValue.Str("ann@x.com"), field.args["value"])
        assertEquals("Text", field.slots["label"]!!.single().name)
        assertEquals(PValue.Str("Email"), field.slots["label"]!!.single().positional[0])
        val btn = col.children[3]
        assertEquals("Sign in", (btn.children.single().positional[0] as PValue.Str).s)
        assertFalse(btn.args.containsKey("onClick"))
        assertTrue(p.warnings.toString(), p.warnings.isEmpty())
    }

    @Test fun scaffoldWithLazyColumn() {
        val src = """
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen() {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Contacts") }) },
        floatingActionButton = { FloatingActionButton(onClick = {}) { Icon(Icons.Default.Add, contentDescription = "Add") } }
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            items(listOf("Ann", "Bob", "Cy")) { name ->
                Text(text = name, modifier = Modifier.padding(16.dp))
            }
        }
    }
}
@Preview(name = "List", widthDp = 360, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable fun ListPreview() { ListScreen() }
"""
        val p = previews("S.kt" to src).single()
        assertEquals("List", p.title); assertEquals(360, p.widthDp); assertEquals(640, p.heightDp); assertTrue(p.night)
        val sc = p.root.single()
        assertEquals("Scaffold", sc.name)
        assertEquals("TopAppBar", sc.slots["topBar"]!!.single().name)
        assertEquals("Contacts", (sc.slots["topBar"]!!.single().slots["title"]!!.single().positional[0] as PValue.Str).s)
        assertEquals(PValue.Ref("Icons.Default.Add"), sc.slots["floatingActionButton"]!!.single().children.single().positional[0])
        val lazy = sc.children.single()
        assertEquals(listOf("Ann", "Bob", "Cy"), lazy.children.map { (it.args["text"] as PValue.Str).s })
    }

    @Test fun cardListWithMaterialRefs() {
        val src = """
@Composable
fun Item(title: String, subtitle: String) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = Color.Gray.copy(alpha = 0.5f))
            }
        }
    }
}
@Preview @Composable fun P() { Column { Item("One", "first"); Item("Two", "second") } }
"""
        val p = previews("C.kt" to src).single()
        val cards = p.root.single().children
        assertEquals(2, cards.size)
        val col = cards[0].children.single().children[2]
        assertEquals(PValue.Ref("MaterialTheme.typography.titleMedium"), col.children[0].args["style"])
        assertEquals(PValue.ColorV(0x80888888L), col.children[1].args["color"])
        assertEquals(PValue.PCall("RoundedCornerShape", listOf(PValue.Num(12.0, "dp")), emptyMap()), cards[0].args["shape"])
    }

    @Test fun stringResources() {
        val src = """
@Composable fun A() { Text(stringResource(R.string.app_name)); Text(stringResource(id = R.string.missing)) }
@Preview @Composable fun P() { A() }
"""
        val p = previews("A.kt" to src, strings = ComposePreviewParser.parseStrings("""<resources><string name="app_name">My App &amp; Co</string></resources>""")).single()
        assertEquals(PValue.Str("My App & Co"), p.root[0].positional[0])
        assertEquals(PValue.Str("missing"), p.root[1].positional[0])
        assertTrue(p.warnings.contains(PreviewMsg.missingString("missing")))
    }

    @Test fun unknownComposableBecomesPlaceholder() {
        val src = """
@Preview @Composable fun P() { Column { FancyChart(data = listOf(1, 2)) { Text("inside") } } }
"""
        val p = previews("U.kt" to src).single()
        val fancy = p.root.single().children.single()
        assertTrue(fancy.placeholder); assertEquals("FancyChart", fancy.name)
        assertEquals("Text", fancy.children.single().name)
        assertEquals(listOf(PreviewMsg.cannotPreview("FancyChart")), p.warnings)
    }

    @Test fun defaultsRecursionAndLoops() {
        val src = """
@Composable fun Row3(label: String = "x", count: Int = 2) { repeat(count) { i -> Text("${'$'}label${'$'}i") } }
@Composable fun Loop() { Loop() }
@Composable fun Choose(flag: Boolean = true) { if (flag) Text("yes") else Text("no") }
@Preview @Composable fun A() { Row3(count = 3); Row3("y") ; Choose(); Choose(false) }
@Preview @Composable fun B() { Loop() }
@Preview @Composable fun C() { for (n in 1..3) { Text("n=${'$'}n") } ; listOf("a", "b").forEach { Text(it) } }
"""
        val ps = previews("D.kt" to src).associateBy { it.name }
        assertEquals(listOf("x0", "x1", "x2", "y0", "y1", "yes", "no"), ps["A"]!!.root.map { (it.positional[0] as PValue.Str).s })
        assertTrue(ps["B"]!!.warnings.contains(PreviewMsg.recursive("Loop")))
        assertEquals(listOf("n=1", "n=2", "n=3", "a", "b"), ps["C"]!!.root.map { (it.positional[0] as PValue.Str).s })
    }

    @Test fun nonConstantConditionWarnsAndTakesFirstBranch() {
        val src = """
@Composable fun Screen(loading: Boolean) { if (loading) Text("loading") else Text("done") }
@Preview @Composable fun P() { var x by remember { mutableStateOf(compute()) }; if (x) Text("a") else Text("b") }
"""
        val p = previews("N.kt" to src).single()
        assertEquals("a", (p.root.single().positional[0] as PValue.Str).s)
        assertEquals(1, p.warnings.size); assertTrue(p.warnings[0].startsWith("The condition at line"))
    }

    @Test fun previewWithRequiredParametersIsSkippedWithWarning() {
        val src = "@Preview @Composable fun P(name: String) { Text(name) }"
        val p = previews("R.kt" to src).single()
        assertTrue(p.root.isEmpty()); assertEquals(listOf(PreviewMsg.needsParams("P")), p.warnings)
    }

    @Test fun previewAnnotationParameters() {
        val src = """
@Preview(name = "Dark", showBackground = true, backgroundColor = 0xFF101010, showSystemUi = true)
@Preview(name = "Light", widthDp = 200)
@Composable fun P() { Text("x") }
"""
        val ps = previews("P.kt" to src)
        assertEquals(listOf("Dark", "Light"), ps.map { it.title })
        assertEquals(0xFF101010L, ps[0].backgroundColor); assertTrue(ps[0].showSystemUi); assertEquals(200, ps[1].widthDp)
    }

    @Test fun stringsXml() {
        val m = ComposePreviewParser.parseStrings("""
<resources>
  <string name="a">It\'s "ok"</string>
  <string name="b" translatable="false">Line1\nLine2</string>
  <string name="c">&lt;b&gt; &amp; more</string>
  <string name="d">"quoted"</string>
</resources>""")
        assertEquals("It's \"ok\"", m["a"]); assertEquals("Line1\nLine2", m["b"]); assertEquals("<b> & more", m["c"]); assertEquals("quoted", m["d"])
    }

    @Test fun hasPreviewsIsCheap() {
        assertTrue(ComposePreviewParser.hasPreviews(template))
        assertFalse(ComposePreviewParser.hasPreviews("fun main() {}"))
    }

    @Test fun garbageNeverThrowsOrHangs() {
        val samples = listOf(template, theme, "@Preview @Composable fun P() { Column(Modifier.padding(8.dp)) { Text(\"a\") ; Button(onClick = {}) { Text(\"b\") } } }")
        val start = System.currentTimeMillis()
        for (s in samples) {
            var cut = 1
            while (cut < s.length) {
                ComposePreviewParser.findPreviews(mapOf("X.kt" to s.substring(0, cut)))
                cut += 7
            }
        }
        for (g in listOf("", "@", "@Preview", "@Preview(", "fun", "@Composable fun P() {", "@Preview @Composable fun P() { Text( }", "\"\"\"", "@Preview @Composable fun P() = Column {", "}}}}{{{{", "@Preview @Composable fun P() { ${"(".repeat(300)} }")) {
            ComposePreviewParser.findPreviews(mapOf("G.kt" to g))
        }
        assertTrue(System.currentTimeMillis() - start < 15000)
    }

    @Test fun elementCapIsEnforced() {
        val src = "@Preview @Composable fun P() { repeat(20) { repeat(20) { repeat(20) { Text(\"x\") } } } }"
        val p = ComposePreviewParser.findPreviews(mapOf("B.kt" to src)).single()
        assertTrue(flat(p.root).size <= 2001)
        assertTrue(p.warnings.contains(PreviewMsg.TOO_MANY))
    }

    @Test fun everyWarningIsTranslated() {
        val ws = listOf(PreviewMsg.cannotPreview("X"), PreviewMsg.needsParams("X"), PreviewMsg.unreadable(3), PreviewMsg.notConstant(2), PreviewMsg.sampleData(9),
            PreviewMsg.TOO_MANY, PreviewMsg.recursive("A"), PreviewMsg.missingString("k"), PreviewMsg.customAnnotation("Z"))
        for (w in ws) assertTrue(w, PreviewTexts.exact.containsKey(w) || PreviewTexts.rules.any { it.first.matches(w) })
        for ((re, t) in PreviewTexts.rules) {
            val n = re.toPattern().matcher("").groupCount()
            assertTrue(re.pattern, Regex("\\$(\\d)").findAll(t).all { it.groupValues[1].toInt() in 1..n })
        }
    }
}
