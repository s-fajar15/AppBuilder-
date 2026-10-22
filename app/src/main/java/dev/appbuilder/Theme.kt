package dev.appbuilder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
class Palette(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val ink: Color,
    val onInk: Color,
    val secondary: Color,
    val border: Color,
    val accent: Color,
    val success: Color,
    val error: Color,
    val keyword: Color,
    val type: Color,
    val string: Color,
    val comment: Color,
    val card: Color,
    val okBg: Color,
    val okFg: Color,
    val badBg: Color,
    val badFg: Color,
)

val LightPalette = Palette(
    dark = false,
    bg = Color(0xFFF4F3EF), surface = Color(0xFFEDEBE4),
    ink = Color(0xFF15171A), onInk = Color(0xFFFFFFFF),
    secondary = Color(0xFF5E646B), border = Color(0xFFE1DFD8),
    accent = Color(0xFFFF4F00), success = Color(0xFF12805C), error = Color(0xFFD92D20),
    keyword = Color(0xFFD9480F), type = Color(0xFF2F5BD3),
    string = Color(0xFF1F7A3D), comment = Color(0xFF8A8F94),
    card = Color(0xFFFFFFFF), okBg = Color(0xFFDDF3E4), okFg = Color(0xFF14532D), badBg = Color(0xFFFBE1DC), badFg = Color(0xFF8A1C0B),
)

val DarkPalette = Palette(
    dark = true,
    bg = Color(0xFF111315), surface = Color(0xFF24282C),
    ink = Color(0xFFE8E8E6), onInk = Color(0xFF111315),
    secondary = Color(0xFF9AA0A6), border = Color(0xFF2A2D2F),
    accent = Color(0xFFFF4F00), success = Color(0xFF3DBF92), error = Color(0xFFF2685C),
    keyword = Color(0xFFFF8F5F), type = Color(0xFF7AA2FF),
    string = Color(0xFF6CC38A), comment = Color(0xFF7C8186),
    card = Color(0xFF1C1F22), okBg = Color(0xFF17352A), okFg = Color(0xFF8FE3B0), badBg = Color(0xFF3F1A15), badFg = Color(0xFFF4A99F),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

val GoogleSans = FontFamily(
    Font(R.font.google_sans_regular, FontWeight.Normal),
    Font(R.font.google_sans_medium, FontWeight.Medium),
    Font(R.font.google_sans_bold, FontWeight.Bold),
)
val GoogleSansCode = FontFamily(Font(R.font.google_sans_code_semibold, FontWeight.SemiBold))

object T {
    val title = TextStyle(fontFamily = GoogleSans, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.5).sp)
    val section = TextStyle(fontFamily = GoogleSans, fontWeight = FontWeight.Medium, fontSize = 17.sp)
    val body = TextStyle(fontFamily = GoogleSans, fontWeight = FontWeight.Normal, fontSize = 15.sp)
    val button = TextStyle(fontFamily = GoogleSans, fontWeight = FontWeight.Medium, fontSize = 15.sp)
    val secondary = TextStyle(fontFamily = GoogleSans, fontWeight = FontWeight.Normal, fontSize = 13.sp)
    val caption = TextStyle(fontFamily = GoogleSans, fontWeight = FontWeight.Medium, fontSize = 11.sp)
    val code = TextStyle(fontFamily = GoogleSansCode, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
}

@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides if (dark) DarkPalette else LightPalette, content = content)
}
