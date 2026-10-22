package dev.appbuilder

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every fixed English label in the newer screens must have an Indonesian translation. */
class UiTextsTest {
    private fun dir(): File = listOf("src/main/java/dev/appbuilder", "main/java/dev/appbuilder", "app/src/main/java/dev/appbuilder").map(::File).first { it.isDirectory }
    private val exact = GitTexts.exact + GitHubTexts.exact + EditorTexts.exact + PreviewTexts.exact + DiagTexts.exact
    private val rules = GitTexts.rules + GitHubTexts.rules + EditorTexts.rules + PreviewTexts.rules + DiagTexts.rules
    private val base = setOf("Cancel", "Wrap", "A-", "A+")
    private fun ok(s: String) = s in base || exact.containsKey(s) || rules.any { it.first.matches(s) }

    @Test fun literalsInNewScreensAreTranslated() {
        val calls = Regex("""\b(?:Txt|Btn|SheetItem|Field|ToolKey|FilterChip|Pill|TopBar|SettingCard)\(\s*"((?:[^"\\$]|\\.)*)"""")
        val bad = ArrayList<String>()
        for (f in listOf("GitScreens.kt", "GitHubScreens.kt", "PreviewScreens.kt", "BuildUi.kt", "Editor.kt")) {
            val src = File(dir(), f).readText()
            for (m in calls.findAll(src)) {
                val s = m.groupValues[1]
                if (s.isNotBlank() && !ok(s)) bad += "$f: $s"
            }
        }
        assertTrue("untranslated:\n" + bad.distinct().joinToString("\n"), bad.isEmpty())
    }
}
