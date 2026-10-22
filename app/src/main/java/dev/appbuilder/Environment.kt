package dev.appbuilder

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import org.xmlpull.v1.XmlPullParser
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.provider.OpenableColumns
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/** Applied through GRADLE_USER_HOME/init.d. Only acts when App Builder passes a signing key, so project files stay untouched. */
private val SIGNING_INIT = """
allprojects {
    afterEvaluate { p ->
        def store = System.getenv('AB_STORE')
        if (store != null && p.plugins.hasPlugin('com.android.application')) {
            def android = p.extensions.getByName('android')
            def cfg = android.signingConfigs.maybeCreate('appBuilderRelease')
            cfg.storeFile = new File(store)
            cfg.storePassword = System.getenv('AB_STORE_PASS')
            cfg.keyAlias = System.getenv('AB_KEY_ALIAS')
            cfg.keyPassword = System.getenv('AB_KEY_PASS')
            android.buildTypes.release.signingConfig = cfg
        }
    }
}
""".trimStart()

private const val REPO_URL = "https://dl.google.com/android/repository/repository2-3.xml"
private const val REPO_BASE = "https://dl.google.com/android/repository/"
private const val DAY_MS = 24L * 3600 * 1000

private class RepoPkg(val path: String, val url: String, val size: Long, val sha1: String?)

class PkgEntry(val id: String, val label: String, val installed: Boolean, val sizeMb: Long?)
class PkgList(val entries: List<PkgEntry>, val offline: Boolean)

private fun versionKey(v: String): List<Int> = v.split('.').map { it.toIntOrNull() ?: 0 }
private val versionDesc = Comparator<String> { a, b ->
    val x = versionKey(a); val y = versionKey(b)
    for (i in 0 until maxOf(x.size, y.size)) {
        val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
        if (c != 0) return@Comparator -c
    }
    0
}

class EnvComponent(val id: String, val title: String, val dir: String, val check: String, val url: String?, val sha256: String?)

object EnvStatus {
    val messages = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Set by BuildEnvironment so every install, download and export also shows a notification. */
    @Volatile var listener: ((String, String?) -> Unit)? = null
    fun set(id: String, m: String?) {
        messages.value = if (m == null) messages.value - id else messages.value + (id to m)
        listener?.invoke(id, m)
    }
}

/**
 * The build environment lives entirely in the app's own storage (filesDir/env).
 * Component sources can be overridden in env/catalog.json:
 * [{"id":"jdk","url":"https://...","sha256":"..."}]
 */
class BuildEnvironment(private val context: Context) {
    val root = File(context.filesDir, "env").apply { mkdirs() }
    val catalogFile = File(root, "catalog.json")

    init { EnvStatus.listener = { id, m -> downloadNotice(id, m) } }

    private fun downloadNotice(id: String, m: String?) {
        val nid = 100 + (id.hashCode() and 0xff)
        when {
            m == null -> BuildService.clear(context, nid)
            m.startsWith("Failed") -> { BuildService.clear(context, nid); BuildService.notifyResult(context, tr("Download failed"), "$id · ${tr(m)}") }
            else -> {
                val pct = Regex("(\\d+)%").find(m)?.groupValues?.get(1)?.toIntOrNull()
                BuildService.progress(context, tr("Setting up the environment"), "$id · ${tr(m)}", pct, false, nid)
            }
        }
    }

    private val defaults = listOf(
        EnvComponent("jdk", "JDK", "jdk", "bin/java", "github://s-fajar15/OpenJDK-21/oepnjdk-21", null),
        EnvComponent("gradle", "Gradle", "gradle", "lib", "https://services.gradle.org/distributions/gradle-8.11.1-bin.zip", null),
        EnvComponent("platform", "Android Platform", "android-sdk", "platforms", null, null),
        EnvComponent("buildtools", "Build Tools", "android-sdk", "build-tools", null, null),
    )

    fun components(): List<EnvComponent> {
        val overrides = HashMap<String, Pair<String?, String?>>()
        try {
            if (catalogFile.exists()) {
                val arr = JSONArray(catalogFile.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    overrides[o.getString("id")] = o.optString("url").ifEmpty { null } to o.optString("sha256").ifEmpty { null }
                }
            }
        } catch (_: Exception) { /* a broken catalog must not break the app */ }
        return defaults.map { c ->
            val o = overrides[c.id]
            if (o == null) c else EnvComponent(c.id, c.title, c.dir, c.check, o.first ?: c.url, o.second ?: c.sha256)
        }
    }

    fun home(c: EnvComponent): File {
        val base = File(root, c.dir)
        if (File(base, c.check).exists()) return base
        return base.listFiles { f -> f.isDirectory }?.firstOrNull { File(it, c.check).exists() } ?: base
    }

    fun installed(c: EnvComponent): Boolean {
        val f = File(home(c), c.check)
        return f.exists() && (!f.isDirectory || f.list()?.isNotEmpty() == true)
    }

    fun missing(): List<EnvComponent> = components().filterNot(::installed)

    fun component(id: String) = components().first { it.id == id }
    fun javaHome(): File = home(component("jdk"))
    /** Default Gradle: 8.11.1 when present, otherwise the newest installed one. */
    fun gradleHome(): File {
        val dists = File(root, "gradle").listFiles { f -> f.isDirectory && File(f, "lib").isDirectory }?.toList().orEmpty()
        return dists.firstOrNull { it.name == "gradle-8.11.1" } ?: dists.maxByOrNull { it.name } ?: home(component("gradle"))
    }
    fun sdkRoot(): File = home(component("platform"))

    fun aapt2(): File? {
        val all = File(sdkRoot(), "build-tools").walkTopDown().maxDepth(4).filter { it.isFile && it.name == "aapt2" }.toList()
        return all.firstOrNull { elfArch(it) == "ARM64" } ?: all.firstOrNull()
    }

    // ---- shared-library resolution -------------------------------------------------------

    private val termuxLib = "/data/data/com.termux/files/usr/lib"
    private val termuxAapt2 = "/data/data/com.termux/files/usr/bin/aapt2"
    private val systemLibs = setOf("libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so", "libstdc++.so", "libnativehelper.so")

    /** DT_NEEDED entries of a 64-bit ELF file. */
    /** Program interpreter of a 64-bit ELF: /system/bin/linker64 on Android, /lib/ld-linux-aarch64.so.1 on desktop Linux. */
    fun elfInterpreter(f: File): String? {
        return try {
            RandomAccessFile(f, "r").use { r ->
                val h = ByteArray(64); r.readFully(h)
                if (h[0].toInt() != 0x7f || h[4].toInt() != 2) return null
                val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
                val phoff = bb.getLong(32); val phsz = bb.getShort(54).toInt() and 0xffff; val phnum = bb.getShort(56).toInt() and 0xffff
                for (i in 0 until phnum) {
                    r.seek(phoff + i.toLong() * phsz)
                    val ph = ByteArray(56); r.readFully(ph)
                    val b = ByteBuffer.wrap(ph).order(ByteOrder.LITTLE_ENDIAN)
                    if (b.getInt(0) == 3) {
                        val off = b.getLong(8); val size = b.getLong(32).toInt()
                        r.seek(off)
                        val s = ByteArray(size); r.readFully(s)
                        return String(s, Charsets.UTF_8).trimEnd('\u0000')
                    }
                }
                null
            }
        } catch (_: Exception) { null }
    }

    /** A JDK built for desktop Linux (glibc) is ARM64 too, but cannot run on Android. */
    fun isGlibcBinary(f: File): Boolean = elfInterpreter(f)?.contains("ld-linux") == true

    fun elfNeeded(f: File): List<String> = elfStrings(f, 1L)
    fun elfSoname(f: File): String? = elfStrings(f, 14L).firstOrNull()

    private fun elfStrings(f: File, wanted: Long): List<String> {
        return try {
        RandomAccessFile(f, "r").use { r ->
            val h = ByteArray(64); r.readFully(h)
            if (h[0].toInt() != 0x7f || h[4].toInt() != 2) return emptyList()
            val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            val phoff = bb.getLong(32); val phsz = bb.getShort(54).toInt() and 0xffff; val phnum = bb.getShort(56).toInt() and 0xffff
            class Seg(val vaddr: Long, val off: Long, val size: Long)
            val loads = ArrayList<Seg>(); var dyn: Seg? = null
            for (i in 0 until phnum) {
                r.seek(phoff + i.toLong() * phsz)
                val ph = ByteArray(56); r.readFully(ph)
                val b = ByteBuffer.wrap(ph).order(ByteOrder.LITTLE_ENDIAN)
                val seg = Seg(b.getLong(16), b.getLong(8), b.getLong(32))
                when (b.getInt(0)) { 1 -> loads += seg; 2 -> dyn = seg }
            }
            val d = dyn ?: return emptyList()
            fun off(v: Long): Long? = loads.firstOrNull { v >= it.vaddr && v < it.vaddr + it.size }?.let { v - it.vaddr + it.off }
            r.seek(d.off)
            val buf = ByteArray(d.size.toInt()); r.readFully(buf)
            val db = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            val needed = ArrayList<Long>(); var strtab = 0L
            var i = 0
            while (i + 16 <= buf.size) {
                val tag = db.getLong(i); val v = db.getLong(i + 8)
                if (tag == 0L) break
                if (tag == wanted) needed += v
                if (tag == 5L) strtab = v
                i += 16
            }
            val base = off(strtab) ?: return emptyList()
            needed.map { n ->
                r.seek(base + n)
                val sb = StringBuilder()
                while (true) { val c = r.read(); if (c <= 0) break; sb.append(c.toChar()) }
                sb.toString()
            }
        }
        } catch (_: Exception) { emptyList() }
    }

    private fun libSearchDirs(): List<File> {
        val jh = javaHome()
        return listOf(compatLibDir(), File(jh, "lib"), File(jh, "lib/server"), File("/system/lib64"))
    }

    /** Binaries whose dependencies must all resolve for a build to start. */
    private fun scanTargets(): List<File> {
        val jh = javaHome()
        val t = ArrayList<File>()
        t += File(jh, "bin/java")
        File(jh, "lib").listFiles { f -> f.isFile && f.name.endsWith(".so") }?.let { t += it }
        File(jh, "lib/server").listFiles { f -> f.isFile && f.name.endsWith(".so") }?.let { t += it }
        compatLibDir().listFiles { f -> f.isFile }?.let { t += it }
        aapt2()?.let { t += it }
        return t.filter { it.exists() }
    }

    fun missingLibs(): List<String> {
        val dirs = libSearchDirs()
        val out = LinkedHashSet<String>()
        for (f in scanTargets()) for (n in elfNeeded(f)) {
            if (n in systemLibs) continue
            if (dirs.none { File(it, n).exists() }) out += n
        }
        return out.toList()
    }

    /** Reads a file as root. The bytes are written by this app, so the copy has the app's own ownership and SELinux label. */
    private fun suRead(path: String): ByteArray? = try {
        val p = ProcessBuilder("su", "-c", "cat '$path'").redirectErrorStream(false).start()
        val data = p.inputStream.readBytes()
        p.errorStream.readBytes()
        p.waitFor()
        if (data.size > 4 && data[0].toInt() == 0x7f && data[1].toInt() == 'E'.code) data else null
    } catch (_: Exception) { null }

    /**
     * The Android JDK build links against Termux libraries (libandroid-shmem.so, ...) through a path
     * inside Termux's private folder. Another app cannot read that folder, so with root we copy the
     * needed libraries into our own env/lib-compat. Never touches /system.
     */
    /**
     * Bionic matches version requirements (DT_VERNEED) against a library's SONAME, not its file name.
     * Android's own libz has SONAME "libz.so", so a copy renamed to libz.so.1 still fails for libzip.so.
     * Without root we rewrite the reference inside our own JDK copy so it asks for "libz.so" instead.
     */
    private fun patchZlibNeeded(): List<String> {
        val from = "libz.so.1\u0000".toByteArray()
        val to = "libz.so\u0000\u0000\u0000".toByteArray()
        val jh = javaHome()
        val files = listOf(File(jh, "lib"), File(jh, "lib/server")).flatMap { d ->
            d.listFiles { f -> f.isFile && f.name.endsWith(".so") }?.toList() ?: emptyList()
        }
        val done = ArrayList<String>()
        for (f in files) {
            val b = f.readBytes()
            var changed = false
            var i = 0
            while (i <= b.size - from.size) {
                var m = true
                for (k in from.indices) if (b[i + k] != from[k]) { m = false; break }
                if (m) { for (k in to.indices) b[i + k] = to[k]; changed = true; i += from.size } else i++
            }
            if (changed) { f.writeBytes(b); done += f.name }
        }
        return done
    }

    /** Zip extraction drops permission bits. Repairs an already-installed JDK in place. */
    fun ensureExecutables(): List<String> {
        val jh = javaHome()
        val fixed = ArrayList<String>()
        listOf(File(jh, "lib/jspawnhelper"), File(jh, "lib/jexec")).forEach { if (it.exists() && !it.canExecute()) { it.setExecutable(true, false); fixed += it.name } }
        File(jh, "bin").listFiles { f -> f.isFile && !f.canExecute() }?.forEach { it.setExecutable(true, false); fixed += it.name }
        aapt2()?.let { if (!it.canExecute()) { it.setExecutable(true, false); fixed += it.name } }
        return fixed
    }

    fun fixMissingLibs(): String {
        val log = StringBuilder()
        ensureExecutables().takeIf { it.isNotEmpty() }?.let { log.appendLine("Made executable: " + it.joinToString()) }
        val z = File(compatLibDir(), "libz.so.1")
        if (z.exists() && elfSoname(z) != "libz.so.1") {
            val real = suRead("$termuxLib/libz.so.1")
            if (real != null) {
                z.writeBytes(real); z.setExecutable(true, false)
                log.appendLine("Replaced libz.so.1 with the Termux build (correct SONAME)")
            } else {
                val n = patchZlibNeeded()
                if (n.isNotEmpty()) log.appendLine("No root: pointed zlib reference to system libz in " + n.joinToString())
            }
        }
        if (aapt2()?.let { elfArch(it) } != "ARM64") {
            val bytes = suRead(termuxAapt2)
            if (bytes != null) {
                val dest = File(File(sdkRoot(), "build-tools/termux").apply { mkdirs() }, "aapt2")
                dest.writeBytes(bytes); dest.setExecutable(true, false)
                log.appendLine("Copied aapt2 (ARM64) from Termux")
            }
        }
        repeat(8) {
            val missing = missingLibs()
            if (missing.isEmpty()) return log.toString().trim()
            var progress = false
            for (name in missing) {
                val bytes = suRead("$termuxLib/$name")
                if (bytes != null) {
                    File(compatLibDir(), name).apply { writeBytes(bytes); setExecutable(true, false) }
                    log.appendLine("Copied $name from Termux"); progress = true
                }
            }
            if (!progress) {
                log.appendLine("Missing libraries: " + missing.joinToString())
                log.appendLine("Grant root to App Builder, or use JDK > Add library (.so).")
                return log.toString().trim()
            }
        }
        return log.toString().trim()
    }

    fun importLibrary(uri: Uri) {
        var name = "lib.so"
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
        }
        val dest = File(compatLibDir(), name)
        context.contentResolver.openInputStream(uri)?.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        dest.setExecutable(true, false)
    }

    // ---- signing keys ----

    fun keystoreDir(): File = File(context.filesDir, "keystores").apply { mkdirs() }

    /** Uses the JDK's own keytool, so no extra dependency is needed. Returns null on success, otherwise the error text. */
    fun createKey(store: File, alias: String, pass: String, cn: String, years: Int): String? {
        val keytool = File(javaHome(), "bin/keytool")
        if (!keytool.exists()) return "keytool was not found in the JDK"
        val dn = "CN=" + cn.replace(Regex("[,=+<>#;\\\"]"), " ")
        val out = run(listOf(
            keytool.path, "-genkeypair", "-keystore", store.path, "-storetype", "PKCS12", "-alias", alias,
            "-keyalg", "RSA", "-keysize", "2048", "-validity", (years * 365).toString(),
            "-storepass", pass, "-keypass", pass, "-dname", dn,
        ))
        return if (store.exists()) null else out
    }

    fun verifyKey(store: File, alias: String, pass: String): Boolean {
        val keytool = File(javaHome(), "bin/keytool")
        if (!keytool.exists()) return false
        return run(listOf(keytool.path, "-list", "-keystore", store.path, "-storepass", pass, "-alias", alias)).contains("fingerprint", true)
    }

    // ---- download sources ----

    /** Stores a download URL for one component in env/catalog.json. */
    fun setSource(id: String, url: String) {
        val arr = try { if (catalogFile.exists()) JSONArray(catalogFile.readText()) else JSONArray() } catch (_: Exception) { JSONArray() }
        val out = JSONArray()
        var done = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") == id) { o.put("url", url); done = true }
            out.put(o)
        }
        if (!done) out.put(org.json.JSONObject().put("id", id).put("url", url))
        catalogFile.writeText(out.toString(2))
    }

    /** Replaces the catalog with a JSON file published by whoever maintains the toolchain archives. Throws if it is not valid. */
    fun fetchCatalog(url: String) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 30000
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val arr = JSONArray(text)
        for (i in 0 until arr.length()) arr.getJSONObject(i).getString("id")
        catalogFile.writeText(text)
    }

    // ---- Android SDK license (explicit user consent only) ----

    fun licensesAccepted(): Boolean = File(sdkRoot(), "licenses/android-sdk-license").exists()

    /** Writes the same acceptance records that sdkmanager --licenses creates. Call only after the user agreed. */
    fun acceptLicenses() {
        val d = File(sdkRoot(), "licenses").apply { mkdirs() }
        File(d, "android-sdk-license").writeText(
            "\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n8933bad161af4178b1185d1a37fbf41ea5269c55\n" +
                "d56f5187479451eabf01fb78af6dfcb131a6481e\ne9acab5b5fbb560a72cfaecce8946896ff6aab9d\n",
        )
        File(d, "android-sdk-preview-license").writeText("\n84831b9409646a918e30573bab4c9c91346d8abd\n")
    }

    // ---- process environment shared by builds and diagnostics ----

    /**
     * Android has /system/lib64/libz.so but not libz.so.1, which the JDK's libjli.so asks for.
     * We never touch /system: a private copy named libz.so.1 goes into env/lib-compat,
     * and that directory is put on the child process's LD_LIBRARY_PATH.
     */
    fun compatLibDir(): File {
        val d = File(root, "lib-compat").apply { mkdirs() }
        val target = File(d, "libz.so.1")
        if (!target.exists()) {
            listOf("/system/lib64/libz.so.1", "/system/lib64/libz.so", "/apex/com.android.runtime/lib64/bionic/libz.so")
                .map(::File).firstOrNull { it.canRead() }?.copyTo(target, true)
        }
        return d
    }

    private fun jhForReport(): File = javaHome()
    /**
     * sqlite-jdbc (used by Room's annotation processor) detects Android by running `uname -o`.
     * Android's own uname answers "Toybox", so it picks the glibc native library and fails to load.
     * This tiny wrapper answers "Android" for that one question and forwards everything else.
     */
    fun shimDir(): File {
        val d = File(root, "shims").apply { mkdirs() }
        val f = File(d, "uname")
        val script = "#!/system/bin/sh\nif [ \"\$1\" = \"-o\" ]; then echo Android; exit 0; fi\nexec /system/bin/uname \"\$@\"\n"
        if (!f.exists() || f.readText() != script) f.writeText(script)
        f.setExecutable(true, false)
        return d
    }

    fun tmpDir(): File = File(root, "tmp").apply { mkdirs() }
    fun homeDir(): File = File(root, "home").apply { mkdirs() }

    fun applyProcessEnv(pb: ProcessBuilder) {
        val jh = javaHome()
        pb.environment().apply {
            put("JAVA_HOME", jh.path)
            put("ANDROID_HOME", sdkRoot().path)
            put("ANDROID_SDK_ROOT", sdkRoot().path)
            put("GRADLE_USER_HOME", File(root, "gradle-home").path)
            put("HOME", homeDir().path)
            put("JAVA_TOOL_OPTIONS", "-Djdk.lang.Process.launchMechanism=FORK -Dfile.encoding=UTF-8 -Djava.io.tmpdir=${tmpDir().path} -Duser.home=${homeDir().path}")
            put("TMPDIR", tmpDir().path)
            put("LD_LIBRARY_PATH", listOf(compatLibDir().path, File(jh, "lib").path, File(jh, "lib/server").path, "/system/lib64").joinToString(":"))
            put("PATH", shimDir().path + ":" + File(jh, "bin").path + ":/system/bin:/system/xbin")
        }
    }

    /** Gradle's own JVM is forked, so tmp/home/native settings must live in GRADLE_USER_HOME, not only on our command line. */
    fun writeGradleUserProperties() {
        val gh = File(root, "gradle-home").apply { mkdirs() }
        File(File(gh, "init.d").apply { mkdirs() }, "appbuilder-signing.gradle").writeText(SIGNING_INIT)
        File(gh, "gradle.properties").writeText(
            "org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8 -Djdk.lang.Process.launchMechanism=FORK -Djava.io.tmpdir=${tmpDir().path} -Duser.home=${homeDir().path}\n" +
                "org.gradle.daemon=false\norg.gradle.vfs.watch=false\norg.gradle.native=false\n",
        )
    }

    /** Reads the ELF header so we can say "x86-64" or "ARM64" instead of failing with a vague exec error. */
    fun elfArch(f: File): String {
        if (!f.exists()) return "missing"
        val h = ByteArray(20)
        val n = f.inputStream().use { it.read(h) }
        if (n < 20 || h[0].toInt() != 0x7f || h[1].toInt() != 'E'.code) return "not an ELF binary"
        return when ((h[18].toInt() and 0xff) or ((h[19].toInt() and 0xff) shl 8)) {
            0xB7 -> "ARM64"; 0x3E -> "x86-64"; 0x28 -> "ARM32"; 0x03 -> "x86"; else -> "unknown"
        }
    }

    private fun run(cmd: List<String>): String {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        applyProcessEnv(pb)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) { p.destroy(); return "timed out" }
        return out.trim().ifEmpty { "(no output, exit ${p.exitValue()})" }
    }

    /** Actually executes the toolchain. Existence of a file proves nothing. */
    fun diagnose(): String = buildString {
        val fixLog = fixMissingLibs()
        if (fixLog.isNotEmpty()) { appendLine(fixLog); appendLine() }
        val java = File(javaHome(), "bin/java")
        val still = missingLibs()
        appendLine("Unresolved libraries: " + if (still.isEmpty()) "none" else still.joinToString())
        appendLine("JDK: ${javaHome().path}")
        appendLine("java binary: ${elfArch(java)}")
        appendLine("java type: " + if (isGlibcBinary(java)) "Linux glibc (cannot run on Android)" else "Android (bionic)")
        File(jhForReport(), "lib/jspawnhelper").let { appendLine("jspawnhelper: " + if (!it.exists()) "absent" else if (it.canExecute()) "executable" else "NOT executable") }
        val zc = File(compatLibDir(), "libz.so.1")
        appendLine("libz.so.1 compat: " + if (zc.exists()) "present, SONAME ${elfSoname(zc)}" else "not present")
        appendLine(if (java.exists()) try { run(listOf(java.path, "-version")) } catch (e: Exception) { "java failed to start: ${e.message}" } else "java not found")
        appendLine()
        val launcher = File(gradleHome(), "lib").listFiles { f -> f.name.startsWith("gradle-launcher-") }?.firstOrNull()
        appendLine("Gradle: " + (launcher?.name ?: "launcher jar not found"))
        if (launcher != null && java.exists()) {
            writeGradleUserProperties()
            appendLine(try {
                run(listOf(java.path, "-Djava.io.tmpdir=${tmpDir().path}", "-Duser.home=${homeDir().path}", "-classpath", launcher.path, "org.gradle.launcher.GradleMain", "--version"))
                    .lines().filter { it.isNotBlank() }.take(8).joinToString("\n")
            } catch (e: Exception) { "Gradle failed to start: ${e.message}" })
        }
        appendLine()
        val jar = File(sdkRoot(), "platforms").listFiles { f -> f.isDirectory }?.map { File(it, "android.jar") }?.filter { it.exists() }
        appendLine("Platform: " + (jar?.joinToString { it.parentFile!!.name }?.ifEmpty { null } ?: "no android.jar found"))
        appendLine()
        val a = aapt2()
        appendLine("aapt2: " + (a?.path ?: "not found in build-tools"))
        if (a == null) appendLine("build-tools contains: " + (File(sdkRoot(), "build-tools").list()?.joinToString()?.ifEmpty { null } ?: "nothing"))
        if (a != null) {
            val arch = elfArch(a)
            appendLine("aapt2 binary: $arch")
            appendLine(if (arch == "ARM64") try { run(listOf(a.path, "version")) } catch (e: Exception) { "aapt2 failed to start: ${e.message}" }
            else "This aapt2 cannot run on an ARM64 phone. Import ARM64 build tools.")
        }
    }.trim()

    // ---- operations (blocking: call from a background thread) ----

    fun runSafely(id: String, block: () -> Unit) {
        try { block(); EnvStatus.set(id, null) } catch (e: Exception) { EnvStatus.set(id, "Failed: ${e.message ?: e.javaClass.simpleName}") }
    }

    /** "github://owner/repo/tag" becomes the direct link of the first suitable uploaded asset of that release. */
    private fun resolveSource(src: String): String {
        if (!src.startsWith("github://")) return src
        val (owner, repo, tag) = src.removePrefix("github://").split("/").also { require(it.size == 3) { "Invalid GitHub source" } }
        val conn = URL("https://api.github.com/repos/$owner/$repo/releases/tags/$tag").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 30000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        if (conn.responseCode !in 200..299) throw IllegalStateException("GitHub: HTTP ${conn.responseCode}")
        val assets = JSONArray(org.json.JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONArray("assets").toString())
        val names = (0 until assets.length()).map { assets.getJSONObject(it) }
        val pick = names.firstOrNull { Regex("(?i)jdk.*(arm64|aarch64).*\\.(zip|tar\\.gz|tgz)$").containsMatchIn(it.getString("name")) }
            ?: names.firstOrNull { Regex("(?i)\\.(zip|tar\\.gz|tgz)$").containsMatchIn(it.getString("name")) }
            ?: throw IllegalStateException("No downloadable file in that release")
        return pick.getString("browser_download_url")
    }

    private fun downloadFile(url: String, tmp: File, onPct: (Int) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 30000
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
        val total = conn.contentLengthLong
        var done = 0L; var last = -1
        conn.inputStream.use { i -> tmp.outputStream().use { o ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = i.read(buf); if (n < 0) break
                o.write(buf, 0, n); done += n
                if (total > 0) { val pct = (done * 100 / total).toInt(); if (pct != last) { last = pct; onPct(pct) } }
            }
        } }
    }

    /**
     * A project decides its own Gradle version (gradle-wrapper.properties). Returns the matching installation,
     * downloading it from services.gradle.org first when needed. A null version means "use the default".
     */
    fun ensureGradle(version: String?, progress: (String) -> Unit): File {
        if (version == null) return gradleHome()
        val dir = File(root, "gradle/gradle-$version")
        if (File(dir, "lib").isDirectory) return dir
        val tmp = File(context.cacheDir, "dl-gradle-$version")
        try {
            progress("Downloading Gradle $version")
            downloadFile("https://services.gradle.org/distributions/gradle-$version-bin.zip", tmp) { pct -> progress("Downloading Gradle $version $pct%") }
            progress("Extracting Gradle $version")
            extract(tmp, File(root, "gradle"))
        } catch (e: Exception) {
            throw IllegalStateException("Gradle $version is required by this project and could not be installed: ${e.message}")
        } finally { tmp.delete() }
        if (!File(dir, "lib").isDirectory) throw IllegalStateException("Gradle $version could not be installed")
        return dir
    }

    // ---- version catalogs: any Gradle, any Android platform, any Build-Tools ----

    /** Google's package list, cached for a day. Only the platform and build-tools entries are kept. */
    private fun repoPackages(): Map<String, RepoPkg> {
        val f = File(root, "repo-cache.xml")
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < DAY_MS
        if (!fresh) {
            try {
                val tmp = File(root, "repo-cache.tmp")
                downloadFile(REPO_URL, tmp) {}
                tmp.copyTo(f, true); tmp.delete()
            } catch (e: Exception) { if (!f.exists()) throw e }
        }
        val wanted = Regex("^(platforms;android-\\d+|build-tools;\\d+\\.\\d+\\.\\d+)$")
        val out = HashMap<String, RepoPkg>()
        f.inputStream().use { input ->
            val p = android.util.Xml.newPullParser()
            p.setInput(input, null)
            var path: String? = null
            var best: RepoPkg? = null
            var inPatches = false
            var url: String? = null; var size = 0L; var sha1: String? = null; var host: String? = null
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "remotePackage" -> { path = p.getAttributeValue(null, "path"); best = null }
                        "patches" -> inPatches = true
                        "archive" -> { url = null; size = 0L; sha1 = null; host = null }
                        "url" -> { val t = p.nextText(); if (!inPatches) url = t.trim() }
                        "size" -> { val t = p.nextText(); if (!inPatches) size = t.trim().toLongOrNull() ?: 0L }
                        "checksum" -> { val t = p.nextText(); if (!inPatches) sha1 = t.trim() }
                        "host-os" -> host = p.nextText().trim()
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "patches" -> inPatches = false
                        "archive" -> {
                            val pa = path; val u = url
                            if (pa != null && u != null && (host == null || host == "linux") && (best == null || host == "linux")) {
                                best = RepoPkg(pa, u, size, sha1)
                            }
                        }
                        "remotePackage" -> {
                            val pa = path; val b = best
                            if (pa != null && b != null && wanted.matches(pa)) out[pa] = b
                            path = null; best = null
                        }
                    }
                }
                ev = p.next()
            }
        }
        return out
    }

    private fun gradleVersions(): List<String> {
        val f = File(root, "gradle-versions.json")
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < DAY_MS
        if (!fresh) {
            try {
                val tmp = File(root, "gradle-versions.tmp")
                downloadFile("https://services.gradle.org/versions/all", tmp) {}
                JSONArray(tmp.readText())
                tmp.copyTo(f, true); tmp.delete()
            } catch (e: Exception) { if (!f.exists()) throw e }
        }
        val arr = JSONArray(f.readText())
        fun str(o: org.json.JSONObject, k: String) = if (o.isNull(k)) "" else o.optString(k)
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optBoolean("snapshot") || o.optBoolean("nightly") || o.optBoolean("broken")) continue
            if (str(o, "rcFor").isNotEmpty() || str(o, "milestoneFor").isNotEmpty()) continue
            val v = str(o, "version")
            if (Regex("^\\d+(\\.\\d+){1,2}$").matches(v)) out += v
        }
        return out.distinct().sortedWith(versionDesc).take(30)
    }

    fun platformInstalled(api: Int): Boolean = File(sdkRoot(), "platforms/android-$api/android.jar").exists()

    /** kind: "gradle", "platform" or "buildtools". Falls back to what is installed when offline. */
    fun listPackages(kind: String): PkgList {
        var offline = false
        val entries: List<PkgEntry> = when (kind) {
            "gradle" -> {
                val local = File(root, "gradle").listFiles { f -> f.isDirectory && f.name.startsWith("gradle-") && File(f, "lib").isDirectory }
                    ?.map { it.name.removePrefix("gradle-") }.orEmpty()
                val remote = try { gradleVersions() } catch (_: Exception) { offline = true; emptyList() }
                (remote + local).distinct().sortedWith(versionDesc).map { PkgEntry(it, "Gradle $it", it in local, null) }
            }
            "platform" -> {
                val repo = try { repoPackages() } catch (_: Exception) { offline = true; emptyMap() }
                val local = File(sdkRoot(), "platforms").listFiles { f -> File(f, "android.jar").exists() }
                    ?.mapNotNull { Regex("^android-(\\d+)$").find(it.name)?.groupValues?.get(1) }.orEmpty()
                val ids = (repo.keys.mapNotNull { Regex("^platforms;android-(\\d+)$").find(it)?.groupValues?.get(1) } + local).distinct().sortedByDescending { it.toInt() }
                ids.map { PkgEntry(it, "Android API $it", it in local, repo["platforms;android-$it"]?.size?.div(1_000_000)) }
            }
            else -> {
                val repo = try { repoPackages() } catch (_: Exception) { offline = true; emptyMap() }
                val local = File(sdkRoot(), "build-tools").listFiles { f -> f.isDirectory && Regex("^\\d+\\.\\d+\\.\\d+$").matches(f.name) }
                    ?.map { it.name }.orEmpty()
                val ids = (repo.keys.mapNotNull { Regex("^build-tools;(.+)$").find(it)?.groupValues?.get(1) } + local).distinct().sortedWith(versionDesc)
                ids.map { PkgEntry(it, "Build-Tools $it", it in local, repo["build-tools;$it"]?.size?.div(1_000_000)) }
            }
        }
        return PkgList(entries, offline)
    }

    private fun checkId(id: String) { require(Regex("^\\d+(\\.\\d+)*$").matches(id)) { "Invalid version" } }

    fun installPackage(kind: String, id: String, progress: (String) -> Unit) {
        checkId(id)
        if (kind == "gradle") { ensureGradle(id, progress); return }
        if (!licensesAccepted()) throw IllegalStateException("Accept the Android SDK license first")
        val path = if (kind == "platform") "platforms;android-$id" else "build-tools;$id"
        val pkg = repoPackages()[path] ?: throw IllegalStateException("$path is not in the Google repository")
        val tmp = File(context.cacheDir, "pkg-$kind-$id.zip")
        val work = File(context.cacheDir, "pkg-$kind-$id")
        try {
            progress("Downloading $id 0%")
            downloadFile(REPO_BASE + pkg.url, tmp) { progress("Downloading $id $it%") }
            pkg.sha1?.let { want ->
                progress("Verifying")
                val md = MessageDigest.getInstance("SHA-1")
                tmp.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
                if (!md.digest().joinToString("") { "%02x".format(it) }.equals(want, true)) throw IllegalStateException("Checksum mismatch")
            }
            progress("Extracting")
            work.deleteRecursively(); work.mkdirs()
            unzip(tmp, work)
            val top = work.listFiles()?.singleOrNull { it.isDirectory } ?: work
            val target = File(sdkRoot(), if (kind == "platform") "platforms/android-$id" else "build-tools/$id")
            target.deleteRecursively(); target.parentFile?.mkdirs()
            if (!top.renameTo(target)) top.copyRecursively(target, true)
            markExecutables(target)
        } finally { tmp.delete(); work.deleteRecursively() }
    }

    fun removePackage(kind: String, id: String) {
        checkId(id)
        when (kind) {
            "gradle" -> File(root, "gradle/gradle-$id").deleteRecursively()
            "platform" -> File(sdkRoot(), "platforms/android-$id").deleteRecursively()
            else -> File(sdkRoot(), "build-tools/$id").deleteRecursively()
        }
    }

    /** The JDK is unpacked into a staging folder first, so a wrong archive can never damage a working JDK. */
    private fun extractComponent(c: EnvComponent, archive: File) {
        if (c.id != "jdk") { extract(archive, File(root, c.dir)); return }
        val staging = File(root, "jdk-staging")
        staging.deleteRecursively()
        try {
            extract(archive, staging)
            val candidates = listOf(File(staging, "bin/java")) + (staging.listFiles { f -> f.isDirectory }?.map { File(it, "bin/java") } ?: emptyList())
            val java = candidates.firstOrNull { it.exists() } ?: throw IllegalStateException("bin/java was not found in this archive")
            val arch = elfArch(java)
            if (arch != "ARM64") throw IllegalStateException("This JDK is $arch, expected ARM64")
            if (isGlibcBinary(java)) throw IllegalStateException("This JDK is a Linux (glibc) build and cannot run on Android. Use an Android (bionic) JDK build.")
            val target = File(root, c.dir)
            target.deleteRecursively()
            if (!staging.renameTo(target)) staging.copyRecursively(target, true)
        } finally { staging.deleteRecursively() }
    }

    fun install(c: EnvComponent) {
        val url = resolveSource(c.url ?: throw IllegalStateException("No download source"))
        val tmp = File(context.cacheDir, "dl-${c.id}")
        try {
            EnvStatus.set(c.id, "Downloading 0%")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000; conn.readTimeout = 30000
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var done = 0L; var last = -1
            conn.inputStream.use { i -> tmp.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    if (total > 0) { val pct = (done * 100 / total).toInt(); if (pct != last) { last = pct; EnvStatus.set(c.id, "Downloading $pct%") } }
                }
            } }
            c.sha256?.let { expected ->
                EnvStatus.set(c.id, "Verifying")
                val md = MessageDigest.getInstance("SHA-256")
                tmp.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
                val actual = md.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, true)) throw IllegalStateException("Checksum mismatch")
            }
            EnvStatus.set(c.id, "Extracting")
            extractComponent(c, tmp)
        } finally { tmp.delete() }
    }

    fun importArchive(c: EnvComponent, uri: Uri) {
        val tmp = File(context.cacheDir, "imp-${c.id}")
        try {
            EnvStatus.set(c.id, "Extracting")
            context.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            extractComponent(c, tmp)
        } finally { tmp.delete() }
    }

    /** Removes only what this component owns. SDK components share a directory, so remove their subfolder. */
    fun remove(c: EnvComponent) {
        if (c.dir == "android-sdk") File(root, "android-sdk/${c.check}").deleteRecursively() else File(root, c.dir).deleteRecursively()
    }

    fun extract(archive: File, dest: File) {
        dest.mkdirs()
        val head = archive.inputStream().use { val b = ByteArray(2); it.read(b); b }
        if (head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) unzip(archive, dest)
        else if (head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()) GZIPInputStream(archive.inputStream().buffered()).use { untar(it, dest) }
        else throw IllegalStateException("Unsupported archive (use .zip or .tar.gz)")
        markExecutables(dest)
        // A package made by "Export package" carries the libraries it needs in lib-compat/.
        File(dest, "lib-compat").takeIf { it.isDirectory }?.let { d ->
            d.listFiles { x -> x.isFile }?.forEach { x -> x.copyTo(File(compatLibDir(), x.name), true).setExecutable(true, false) }
            d.deleteRecursively()
        }
    }

    /**
     * Writes a self-contained ZIP of an installed component together with the shared libraries it relies on.
     * Importing that ZIP on another phone needs neither Termux nor root.
     */
    fun exportComponent(c: EnvComponent, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            val seen = HashSet<String>()
            fun add(name: String, f: File) {
                if (!seen.add(name)) return
                zip.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            when (c.id) {
                "jdk" -> {
                    val h = home(c)
                    val base = h.canonicalPath + File.separator
                    h.walkTopDown().filter { it.isFile && it.canonicalPath.startsWith(base) }.forEach { add(it.relativeTo(h).path, it) }
                }
                "buildtools" -> {
                    val a = aapt2() ?: throw IllegalStateException("aapt2 not found")
                    add(a.relativeTo(sdkRoot()).path, a)
                }
                else -> throw IllegalStateException("Nothing to export")
            }
            compatLibDir().listFiles { x -> x.isFile }?.forEach { add("lib-compat/" + it.name, it) }
        }
    }

    private fun unzip(archive: File, dest: File) {
        val base = dest.canonicalPath + File.separator
        ZipFile(archive).use { zip ->
            for (e in zip.entries()) {
                val out = File(dest, e.name)
                if (!out.canonicalPath.startsWith(base)) continue
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    zip.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                }
            }
        }
    }

    private fun markExecutables(dest: File) {
        dest.walkTopDown().filter { it.isFile }.forEach { f ->
            val parent = f.parentFile?.name ?: ""
            if (parent == "bin" || f.name == "jspawnhelper" || f.name == "jexec" || (f.extension.isEmpty() && f.path.contains("build-tools"))) f.setExecutable(true, false)
        }
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off; while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun readFully(i: InputStream, b: ByteArray, len: Int = b.size): Boolean {
        var n = 0
        while (n < len) { val r = i.read(b, n, len - n); if (r < 0) return false; n += r }
        return true
    }

    private fun skipFully(i: InputStream, count: Long) {
        var left = count
        val sink = ByteArray(8192)
        while (left > 0) { val r = i.read(sink, 0, minOf(left, sink.size.toLong()).toInt()); if (r < 0) return; left -= r }
    }

    /** Minimal tar reader: ustar, GNU long names, pax paths, symlinks, hardlinks, exec bits. */
    private fun untar(input: InputStream, dest: File) {
        val base = dest.canonicalPath + File.separator
        val h = ByteArray(512)
        var longName: String? = null
        while (readFully(input, h)) {
            if (h.all { it.toInt() == 0 }) break
            var name = cstr(h, 0, 100)
            val prefix = cstr(h, 345, 155)
            if (prefix.isNotEmpty()) name = "$prefix/$name"
            val size = cstr(h, 124, 12).trim().ifEmpty { "0" }.toLong(8)
            val type = h[156].toInt().toChar()
            val mode = cstr(h, 100, 8).trim().ifEmpty { "0" }.toInt(8)
            val link = cstr(h, 157, 100)
            val pad = (512 - size % 512) % 512
            if (type == 'L' || type == 'x' || type == 'g') {
                val data = ByteArray(size.toInt()); readFully(input, data); skipFully(input, pad)
                val text = String(data, Charsets.UTF_8)
                if (type == 'L') longName = text.trimEnd('\u0000')
                else if (type == 'x') text.lines().forEach { l -> l.substringAfter(' ', "").takeIf { it.startsWith("path=") }?.let { longName = it.removePrefix("path=") } }
                continue
            }
            name = longName ?: name; longName = null
            val out = File(dest, name)
            val ok = (out.canonicalPath + File.separator).startsWith(base) && out.canonicalPath + File.separator != base
            if (!ok) { skipFully(input, size + pad); continue }
            when (type) {
                '5' -> { out.mkdirs(); skipFully(input, size + pad) }
                '2' -> { out.parentFile?.mkdirs(); try { Files.deleteIfExists(out.toPath()); Files.createSymbolicLink(out.toPath(), Paths.get(link)) } catch (_: Exception) {}; skipFully(input, size + pad) }
                '1' -> { out.parentFile?.mkdirs(); val src = File(dest, link); if (src.exists()) src.copyTo(out, true); skipFully(input, size + pad) }
                '0', '\u0000' -> {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o ->
                        var left = size; val buf = ByteArray(65536)
                        while (left > 0) { val r = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt()); if (r < 0) break; o.write(buf, 0, r); left -= r }
                    }
                    skipFully(input, pad)
                    if (mode and 0b001001001 != 0) out.setExecutable(true, false)
                }
                else -> skipFully(input, size + pad)
            }
        }
    }
}
