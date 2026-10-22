package dev.appbuilder

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.composed
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** One icon family: 24-unit grid, 1.7 stroke, round caps and joins. */
object I {
    const val back = "M15 5l-7 7 7 7"
    const val search = "M11 4a7 7 0 1 0 0 14a7 7 0 0 0 0-14zM20 20l-4-4"
    const val more = "M12 5.5v.01M12 12v.01M12 18.5v.01"
    const val play = "M8 5.5v13l10-6.5z"
    const val plus = "M12 5v14M5 12h14"
    const val upload = "M12 16V4M7 9l5-5 5 5M5 20h14"
    const val download = "M12 4v12M7 11l5 5 5-5M5 20h14"
    const val share = "M16 6.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0M3 12a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0M16 17.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0M7.8 10.8l8.4-3.6M7.8 13.2l8.4 3.6"
    const val folder = "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"
    const val file = "M7 3h7l5 5v13H7zM14 3v5h5"
    const val bolt = "M13 3L5 13.5h6L10 21l8-10.5h-6z"
    const val cube = "M12 3l8 4.5v9L12 21l-8-4.5v-9zM12 12l8-4.5M12 12v9M12 12L4 7.5"
    const val sliders = "M4 7h9M17 7h3M4 17h3M11 17h9M15 4.5v5M9 14.5v5"
    const val chevron = "M9 5l7 7-7 7"
    const val chevronDown = "M5 9l7 7 7-7"
    const val close = "M6 6l12 12M18 6L6 18"
}

@Composable
fun Ico(path: String, modifier: Modifier = Modifier, tint: Color = LocalPalette.current.ink, size: Dp = 22.dp, description: String? = null) {
    val parsed = remember(path) { PathParser().parsePathString(path).toPath() }
    val m = if (description != null) modifier.semantics { contentDescription = description } else modifier
    Canvas(m.size(size)) {
        scale(this.size.width / 24f, Offset.Zero) {
            drawPath(parsed, tint, style = Stroke(width = 1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
fun Txt(
    text: String,
    style: TextStyle = T.body,
    color: Color = LocalPalette.current.ink,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) = androidx.compose.foundation.text.BasicText(
    text = tr(text),
    modifier = modifier,
    style = style.copy(color = color),
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
)

fun Modifier.tap(onClick: () -> Unit, label: String? = null): Modifier = composed {
    val base = Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        role = Role.Button,
        onClick = onClick,
    )
    this.then(if (label != null) base.semantics { contentDescription = label } else base)
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalPalette.current.border))
}

@Composable
fun Btn(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, solid: Boolean = true, icon: String? = null, enabled: Boolean = true, accent: Boolean = false) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    val onAccent = Color(0xFF15171A)
    val fg = when {
        !enabled -> p.secondary
        accent -> onAccent
        solid -> p.onInk
        else -> p.ink
    }
    Row(
        modifier
            .height(52.dp)
            .clip(shape)
            .background(if (accent && enabled) p.accent else if (solid && enabled) p.ink else p.card)
            .border(1.dp, if (accent && enabled) p.accent else if (solid && enabled) p.ink else if (enabled) p.ink else p.border, shape)
            .then(if (enabled) Modifier.tap(onClick) else Modifier),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Ico(icon, tint = fg, size = 18.dp)
            Spacer(Modifier.width(8.dp))
        }
        Txt(label, T.button, fg)
    }
}

@Composable
fun IconBtn(path: String, description: String, onClick: () -> Unit, solid: Boolean = false) {
    val p = LocalPalette.current
    Box(Modifier.size(48.dp).tap(onClick, description), contentAlignment = Alignment.Center) {
        if (solid) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(p.accent), contentAlignment = Alignment.Center) {
                Ico(path, tint = Color(0xFF15171A), size = 22.dp)
            }
        } else Ico(path, size = 22.dp)
    }
}

@Composable
fun TopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconBtn(I.back, "Back", onBack) else Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, T.section, maxLines = 1)
            if (subtitle != null) Txt(subtitle, T.code.copy(fontSize = 11.sp), LocalPalette.current.secondary, maxLines = 1)
        }
        actions()
    }
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, mono: Boolean = false, error: String? = null, readOnly: Boolean = false, keyboard: KeyboardType = KeyboardType.Text) {
    val p = LocalPalette.current
    Column(modifier) {
        Txt(label, T.secondary, p.secondary)
        Spacer(Modifier.height(6.dp))
        val shape = RoundedCornerShape(10.dp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            readOnly = readOnly,
            singleLine = true,
            textStyle = (if (mono) T.code.copy(fontSize = 14.sp) else T.body).copy(color = p.ink),
            cursorBrush = SolidColor(p.accent),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape).background(p.card)
                .border(1.dp, if (error != null) p.error else p.border, shape),
            decorationBox = { inner -> Box(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) { inner() } },
        )
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Txt(error, T.secondary, p.error)
        }
    }
}

@Composable
fun Segmented(options: List<String>, selected: String, onSelect: (String) -> Unit, enabled: List<String> = options) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, p.border, RoundedCornerShape(14.dp))) {
        options.forEach { o ->
            val on = o == selected
            val ok = o in enabled
            Box(
                Modifier.weight(1f).height(44.dp)
                    .background(if (on) p.ink else p.card)
                    .then(if (ok) Modifier.tap({ onSelect(o) }, o) else Modifier),
                contentAlignment = Alignment.Center,
            ) { Txt(o, T.button, if (on) p.onInk else if (ok) p.ink else p.border) }
        }
    }
}

@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier.padding(horizontal = 24.dp).fillMaxWidth().clip(shape)
                .background(p.card).border(1.dp, p.border, shape).padding(vertical = 8.dp),
            content = content,
        )
    }
}

@Composable
fun SheetItem(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).tap(onClick).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
        Txt(label, T.body, if (danger) p.error else p.ink)
    }
}

@Composable
fun Row2(label: String, value: String, mono: Boolean = false) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, T.body, p.secondary, Modifier.weight(1f))
        Txt(value, if (mono) T.code.copy(fontSize = 13.sp) else T.body, p.ink)
    }
    Divider()
}


fun Modifier.card(): Modifier = composed {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(20.dp)
    this.then(Modifier.clip(shape).background(p.card).border(1.dp, p.border, shape))
}

@Composable
fun Pill(text: String, ok: Boolean) {
    val p = LocalPalette.current
    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (ok) p.okBg else p.badBg).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Txt(text, T.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold), if (ok) p.okFg else p.badFg)
    }
}

@Composable
fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.height(40.dp).clip(shape).background(if (selected) p.ink else p.card)
            .border(1.dp, if (selected) p.ink else p.border, shape).tap(onClick, label).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(label, T.button.copy(fontSize = 14.sp), if (selected) p.onInk else p.ink) }
}

@Composable
fun RoundBtn(path: String, description: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.size(48.dp).clip(CircleShape).background(p.card).border(1.dp, p.border, CircleShape).tap(onClick, description), contentAlignment = Alignment.Center) {
        Ico(path, size = 22.dp)
    }
}
