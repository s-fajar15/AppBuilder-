package dev.appbuilder

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class Signing(val file: File, val alias: String, val password: String)

/** Create, import, back up and remove the release signing key. All dialogs live here. */
@Composable
fun SigningManager(env: BuildEnvironment, prefs: Prefs, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = LocalPalette.current
    var mode by remember { mutableStateOf("menu") }
    var alias by remember { mutableStateOf("key0") }
    var pass by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var years by remember { mutableStateOf("25") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<File?>(null) }
    val current = prefs.signing()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            picked = withContext(Dispatchers.IO) {
                var n = "imported.jks"
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { n = c.getString(it) }
                }
                val dest = File(env.keystoreDir(), "${System.currentTimeMillis()}-${n.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
                ctx.contentResolver.openInputStream(uri)?.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                dest
            }
            mode = "import"
        }
    }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val src = current?.file
        if (uri != null && src != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openOutputStream(uri)?.use { o -> src.inputStream().use { it.copyTo(o) } } }.isSuccess }
            Toast.makeText(ctx, tr(if (ok) "Saved" else "Could not save the file"), Toast.LENGTH_SHORT).show()
        }
    }

    Sheet(onClose) {
        when (mode) {
            "menu" -> {
                Txt("Signing key", T.section, modifier = Modifier.padding(20.dp))
                Txt(if (current != null) "${current.alias} · ${current.file.name}" else "Not set", T.code, p.secondary, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                Txt("Passwords are stored privately on this device.", T.secondary, p.secondary, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                SheetItem("Create new key") { error = null; mode = "create" }
                SheetItem("Import key") { picker.launch(arrayOf("*/*")) }
                if (current != null) {
                    SheetItem("Back up key to storage") { backup.launch("release-key.p12") }
                    SheetItem("Remove key", danger = true) {
                        prefs.keyFile = ""; prefs.keyAlias = ""; prefs.keyPass = ""
                        Toast.makeText(ctx, tr("Key removed"), Toast.LENGTH_SHORT).show(); onClose()
                    }
                }
            }
            "create" -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Your name or company", name, { name = it })
                Field("Alias", alias, { alias = it.trim() }, mono = true)
                Field("Password", pass, { pass = it })
                Field("Validity (years)", years, { years = it.filter(Char::isDigit) }, keyboard = KeyboardType.Number, error = error)
                Txt("Back up this key. If you lose it, you cannot publish updates to your app.", T.secondary, p.secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Cancel", { mode = "menu" }, Modifier.weight(1f), solid = false)
                    Btn(if (busy) "Creating key..." else "Create", {
                        val y = years.toIntOrNull()
                        error = when {
                            name.isBlank() -> "Enter your name or company"
                            !Regex("^[A-Za-z0-9]+$").matches(alias) -> "Alias: letters and numbers only"
                            pass.length < 6 -> "Password must be at least 6 characters"
                            y == null || y !in 1..100 -> "Validity must be 1 to 100 years"
                            else -> null
                        }
                        if (error == null && !busy) {
                            busy = true
                            scope.launch {
                                val file = File(env.keystoreDir(), "key-${System.currentTimeMillis()}.p12")
                                val err = withContext(Dispatchers.IO) { env.createKey(file, alias, pass, name.trim(), y!!) }
                                busy = false
                                if (err == null) {
                                    prefs.keyFile = file.path; prefs.keyAlias = alias; prefs.keyPass = pass
                                    Toast.makeText(ctx, tr("Key created"), Toast.LENGTH_LONG).show(); onClose()
                                } else error = err.lines().lastOrNull { it.isNotBlank() }
                            }
                        }
                    }, Modifier.weight(1f), enabled = !busy)
                }
            }
            else -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Alias", alias, { alias = it.trim() }, mono = true)
                Field("Password", pass, { pass = it }, error = error)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Cancel", { picked?.delete(); mode = "menu" }, Modifier.weight(1f), solid = false)
                    Btn("Verify and save", {
                        val f = picked
                        if (f != null && !busy) {
                            busy = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { env.verifyKey(f, alias, pass) }
                                busy = false
                                if (ok) {
                                    prefs.keyFile = f.path; prefs.keyAlias = alias; prefs.keyPass = pass
                                    Toast.makeText(ctx, tr("Key saved"), Toast.LENGTH_SHORT).show(); onClose()
                                } else error = "Wrong alias or password"
                            }
                        }
                    }, Modifier.weight(1f), enabled = !busy)
                }
            }
        }
    }
}
