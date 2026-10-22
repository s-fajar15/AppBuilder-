package dev.appbuilder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Reads the launcher icon out of a built APK. Returns null when the file is missing or is not an APK. */
fun loadApkIcon(ctx: Context, f: File): Bitmap? {
    if (!f.exists()) return null
    return try {
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(f.path, 0) ?: return null
        val ai = info.applicationInfo ?: return null
        ai.sourceDir = f.path
        ai.publicSourceDir = f.path
        val drawable = ai.loadIcon(pm)
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bmp))
        bmp
    } catch (_: Throwable) {
        null
    }
}

private object IconCache {
    private val map = HashMap<String, Bitmap?>()

    @Synchronized
    fun get(ctx: Context, f: File): Bitmap? {
        val key = f.path + ":" + f.lastModified()
        if (!map.containsKey(key)) map[key] = loadApkIcon(ctx, f)
        return map[key]
    }
}

/** The app's real icon when its APK exists, otherwise the lettered tile. */
@Composable
fun AppIcon(apk: File, name: String, size: Int = 44) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, apk.path, apk.lastModified()) {
        value = withContext(Dispatchers.IO) { IconCache.get(ctx, apk) }?.asImageBitmap()
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)),
        )
    } else {
        ProjectTile(name, size)
    }
}
