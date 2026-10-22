package dev.appbuilder

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The launcher icon, drawn in code so About always matches the real icon. */
@Composable
fun BrandMark(side: Dp) {
    val left = remember { PathParser().parsePathString("M44 30H38a6 6 0 0 0-6 6V48a6 6 0 0 1-6 6a6 6 0 0 1 6 6V72a6 6 0 0 0 6 6H44").toPath() }
    val right = remember { PathParser().parsePathString("M64 30H70a6 6 0 0 1 6 6V48a6 6 0 0 0 6 6a6 6 0 0 0-6 6V72a6 6 0 0 1-6 6H64").toPath() }
    Canvas(Modifier.size(side)) {
        scale(this.size.width / 108f, Offset.Zero) {
            drawRoundRect(Color(0xFFFF4F00), size = Size(108f, 108f), cornerRadius = CornerRadius(30f, 30f))
            val st = Stroke(width = 7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(left, Color.White, style = st)
            drawPath(right, Color.White, style = st)
            drawRoundRect(Color.White, topLeft = Offset(46f, 46f), size = Size(16f, 16f), cornerRadius = CornerRadius(3f, 3f))
        }
    }
}

@Composable
private fun AboutRow(k: String, v: String, last: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(k, T.body, LocalPalette.current.secondary, Modifier.weight(1f))
        Txt(v, T.body.copy(fontWeight = FontWeight.Bold))
    }
    if (!last) Divider()
}

@Composable
fun AboutScreen(env: BuildEnvironment, onBack: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val ver = remember {
        try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
            "${pi.versionName ?: "?"} (build $code)"
        } catch (_: Exception) { "?" }
    }
    val gradle = remember { env.gradleHome().name.removePrefix("gradle-").takeIf { it.firstOrNull()?.isDigit() == true } ?: "-" }
    val jdk = remember { if (env.installed(env.component("jdk"))) "Installed" else "Not installed" }
    Column(Modifier.fillMaxSize()) {
        TopBar("About", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BrandMark(96.dp)
                Spacer(Modifier.height(12.dp))
                Txt("App Builder", T.title.copy(fontSize = 26.sp))
                Spacer(Modifier.height(4.dp))
                Txt("Build Android APKs right on Android", T.body, p.secondary)
            }
            Column(Modifier.fillMaxWidth().card().padding(horizontal = 18.dp)) {
                AboutRow("Version", ver)
                AboutRow("Developer", "s.fajar")
                AboutRow("GitHub", "s-fajar15")
                AboutRow("Gradle", gradle)
                AboutRow("JDK", jdk, last = true)
            }
            Spacer(Modifier.height(16.dp))
            Btn("Open GitHub profile", {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/s-fajar15")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, Modifier.fillMaxWidth(), solid = false)
        }
        Txt("Built for Android ARM64 · © 2026 s.fajar", T.secondary, p.secondary, Modifier.fillMaxWidth().padding(bottom = 28.dp).wrapContentWidth(Alignment.CenterHorizontally))
    }
}
