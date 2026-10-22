package dev.appbuilder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** UI language. [indonesian] is Compose state, so changing it recomposes every visible text. */
object L {
    var indonesian by mutableStateOf(false)

    fun apply(setting: String) {
        indonesian = when (setting) {
            "Indonesia" -> true
            "English" -> false
            else -> Locale.getDefault().language in setOf("in", "id")
        }
    }
}

private val ID = mapOf(
    // navigation + common
    "Projects" to "Proyek", "Builds" to "Build", "SDK" to "SDK", "Settings" to "Pengaturan",
    "Cancel" to "Batal", "OK" to "OK", "Accept" to "Setuju", "Add" to "Tambah", "Remove" to "Hapus",
    "Delete" to "Hapus", "Update" to "Perbarui", "Rename" to "Ubah nama", "Back" to "Kembali", "More" to "Lainnya",
    "Search" to "Cari", "Close search" to "Tutup pencarian", "Saved" to "Tersimpan", "Could not save the file" to "Gagal menyimpan file",
    "System" to "Sistem", "Light" to "Terang", "Dark" to "Gelap",
    // projects
    "Search projects" to "Cari proyek", "No projects yet. Create one or import a ZIP." to "Belum ada proyek. Buat baru atau impor ZIP.",
    "No matching projects." to "Tidak ada proyek yang cocok.", "Build failed" to "Build gagal", "New project" to "Proyek baru",
    "Import ZIP" to "Impor ZIP", "Project name" to "Nama proyek", "Package name" to "Nama paket", "Language" to "Bahasa",
    "UI" to "UI", "Jetpack Compose requires Kotlin." to "Jetpack Compose memerlukan Kotlin.", "Min SDK" to "SDK minimum",
    "Target / Compile SDK" to "SDK target / compile", "Location" to "Lokasi", "Create project" to "Buat proyek",
    "Use letters, numbers, space, - or _" to "Gunakan huruf, angka, spasi, - atau _",
    "A project with this name already exists" to "Proyek dengan nama ini sudah ada", "Example: com.example.myapp" to "Contoh: com.example.myapp",
    "Import project" to "Impor proyek", "Android project detected" to "Proyek Android terdeteksi",
    "Project cannot be imported" to "Proyek tidak dapat diimpor", "Build" to "Build", "Compile SDK" to "Compile SDK",
    "Name" to "Nama", "Missing" to "Tidak ada", "Choose a ZIP that contains a Gradle-based Android project." to "Pilih ZIP yang berisi proyek Android berbasis Gradle.",
    "Choose another ZIP" to "Pilih ZIP lain",
    // project screen
    "Files" to "File", "Build and run" to "Build dan jalankan", "This file type cannot be edited" to "Tipe file ini tidak dapat diedit",
    "New file" to "File baru", "Configuration" to "Konfigurasi", "Dependencies" to "Dependensi", "Export ZIP" to "Ekspor ZIP",
    "Delete project" to "Hapus proyek", "New file here" to "File baru di sini", "New name" to "Nama baru", "File name" to "Nama file",
    "This cannot be undone." to "Tindakan ini tidak dapat dibatalkan.", "All project files will be removed from this device." to "Semua file proyek akan dihapus dari perangkat ini.",
    // build
    "Starting Gradle" to "Memulai Gradle", "Build successful" to "Build berhasil", "Build cancelled" to "Build dibatalkan",
    "Build environment not ready" to "Lingkungan build belum siap", "Install or import these components in SDK." to "Pasang atau impor komponen ini di SDK.",
    "No build yet." to "Belum ada build.", "Cancel build" to "Batalkan build", "View result" to "Lihat hasil", "Open SDK" to "Buka SDK",
    "Build debug APK" to "Build APK debug", "Build release APK" to "Build APK release", "Build release AAB" to "Build AAB release",
    "Debug" to "Debug", "Release APK" to "APK Release", "Release AAB" to "AAB Release", "Debug APK" to "APK Debug",
    "Open build log" to "Buka log build", "Hide build log" to "Sembunyikan log build", "Save build log" to "Simpan log build",
    "View build log" to "Lihat log build", "No log yet." to "Belum ada log.", "See the build log below." to "Lihat log build di bawah.",
    "Review SDK license" to "Tinjau lisensi SDK", "License accepted. Build again." to "Lisensi diterima. Build lagi.",
    "Android SDK license" to "Lisensi Android SDK",
    "Building downloads Android SDK packages from Google. They are covered by the Android Software Development Kit License Agreement (developer.android.com/studio/terms). Tap Accept only if you have read it and agree." to
        "Proses build mengunduh paket Android SDK dari Google. Paket itu tunduk pada Perjanjian Lisensi Android SDK (developer.android.com/studio/terms). Ketuk Setuju hanya jika Anda sudah membaca dan menyetujuinya.",
    "Another build is running" to "Build lain sedang berjalan", "No builds yet. Open a project and tap Run." to "Belum ada build. Buka proyek lalu ketuk Run.",
    "Cancelled" to "Dibatalkan", "Set up signing key" to "Siapkan kunci signing",
    "Release builds need a signing key. Create or import one first." to "Build release memerlukan kunci signing. Buat atau impor dulu.",
    // result
    "No APK found" to "APK tidak ditemukan", "No file found" to "File tidak ditemukan", "Install" to "Pasang", "Share" to "Bagikan", "Save to storage" to "Simpan ke penyimpanan",
    "Allow installs from App Builder" to "Izinkan pemasangan dari App Builder",
    "Android needs your permission to install apps from this source. You will be taken to the setting; nothing is changed automatically." to
        "Android memerlukan izin Anda untuk memasang aplikasi dari sumber ini. Anda akan dibawa ke pengaturannya; tidak ada yang diubah otomatis.",
    "Open settings" to "Buka pengaturan",
    // sdk
    "Build environment" to "Lingkungan build", "Installed" to "Terpasang", "Not installed" to "Belum terpasang", "Accepted" to "Diterima", "Not accepted" to "Belum diterima",
    "Run diagnostics" to "Jalankan diagnostik", "Running..." to "Berjalan...", "Import archive" to "Impor arsip", "Add library (.so)" to "Tambah library (.so)",
    "Verify" to "Verifikasi", "Repair (download again)" to "Perbaiki (unduh ulang)", "No download source. Import an archive." to "Belum ada sumber unduhan. Impor arsip atau atur URL.",
    "Export package" to "Ekspor paket", "Exporting" to "Mengekspor", "Package exported" to "Paket diekspor", "Could not export" to "Gagal mengekspor",
    "Android SDK Manager" to "Android SDK Manager", "SDK Platform" to "SDK Platform", "Latest installed" to "Versi terbaru sudah terpasang",
    "Setting up the environment" to "Menyiapkan lingkungan build", "Download failed" to "Unduhan gagal", "Extracting" to "Mengekstrak", "Verifying" to "Memverifikasi",
    "Choose what to build" to "Pilih jenis build", "Log not available" to "Log tidak tersedia", "Build log" to "Log build",
    "Wrap" to "Bungkus", "Application" to "Aplikasi", "Toolchain" to "Toolchain",
    "All" to "Semua", "Success" to "Berhasil", "Failed" to "Gagal", "About" to "Tentang", "Developer" to "Pengembang", "GitHub" to "GitHub",
    "Open GitHub profile" to "Buka profil GitHub", "Build Android APKs right on Android" to "Bangun APK Android langsung di Android",
    "Built for Android ARM64 · © 2026 s.fajar" to "Dibuat untuk Android ARM64 · © 2026 s.fajar",
    "Manage versions" to "Kelola versi", "Gradle versions" to "Versi Gradle", "Android platforms" to "Platform Android", "Build Tools versions" to "Versi Build Tools",
    "Loading..." to "Memuat...", "Offline: showing installed versions only." to "Offline: hanya menampilkan versi yang terpasang.",
    "Set download URL" to "Atur URL unduhan", "Download URL" to "URL unduhan", "Download all" to "Unduh semua", "Update catalog" to "Perbarui katalog",
    "Catalog URL" to "URL katalog", "Catalog updated" to "Katalog diperbarui", "Could not read the catalog" to "Katalog tidak dapat dibaca", "Source saved" to "Sumber disimpan",
    "Everything is already installed" to "Semuanya sudah terpasang", "Nothing to download. Set a source first." to "Tidak ada yang bisa diunduh. Atur sumber dulu.",
    "Android Platform" to "Platform Android", "Build Tools" to "Build Tools", "Gradle" to "Gradle", "JDK" to "JDK",
    "Archives must be built for Android (bionic) on your CPU, as .zip or .tar.gz." to "Arsip harus dibuat untuk Android (bionic) sesuai CPU Anda, berformat .zip atau .tar.gz.",
    // config + deps
    "Save changes" to "Simpan perubahan", "No editable Gradle settings were found in this project." to "Tidak ada pengaturan Gradle yang bisa diedit di proyek ini.",
    "App name" to "Nama aplikasi", "Application ID" to "Application ID", "Version name" to "Nama versi", "Version code" to "Kode versi",
    "Minimum SDK" to "SDK minimum", "Target SDK" to "SDK target", "Kotlin version" to "Versi Kotlin", "Gradle version" to "Versi Gradle",
    "Enter a valid name" to "Masukkan nama yang valid", "Example: 1.0.0" to "Contoh: 1.0.0", "Must be 1 or higher" to "Minimal 1",
    "Use a number from 21" to "Gunakan angka mulai 21", "Must be at least Minimum SDK" to "Minimal sama dengan SDK minimum", "Must be at least Target SDK" to "Minimal sama dengan SDK target",
    "No dependencies declared." to "Belum ada dependensi.", "Add dependency" to "Tambah dependensi", "Popular libraries" to "Library populer",
    "Group" to "Group", "Artifact" to "Artifact", "Version" to "Versi", "managed by BOM" to "diatur oleh BOM",
    "Group is required, e.g. androidx.core" to "Group wajib diisi, mis. androidx.core", "Artifact is required, e.g. core-ktx" to "Artifact wajib diisi, mis. core-ktx", "Invalid version" to "Versi tidak valid",
    "Added" to "Ditambahkan",
    // settings
    "Appearance" to "Tampilan", "Editor font size" to "Ukuran font editor", "Signing key" to "Kunci signing", "Not set" to "Belum diatur",
    "Create new key" to "Buat kunci baru", "Import key" to "Impor kunci", "Remove key" to "Hapus kunci", "Back up key to storage" to "Cadangkan kunci ke penyimpanan",
    "Your name or company" to "Nama Anda atau perusahaan", "Alias" to "Alias", "Password" to "Kata sandi", "Validity (years)" to "Masa berlaku (tahun)",
    "Create" to "Buat", "Verify and save" to "Verifikasi dan simpan", "Key created" to "Kunci dibuat", "Key saved" to "Kunci disimpan", "Key removed" to "Kunci dihapus",
    "Back up this key. If you lose it, you cannot publish updates to your app." to "Cadangkan kunci ini. Jika hilang, Anda tidak bisa merilis pembaruan aplikasi.",
    "Password must be at least 6 characters" to "Kata sandi minimal 6 karakter", "Enter your name or company" to "Isi nama Anda atau perusahaan",
    "Alias: letters and numbers only" to "Alias: hanya huruf dan angka", "Validity must be 1 to 100 years" to "Masa berlaku harus 1 sampai 100 tahun",
    "Wrong alias or password" to "Alias atau kata sandi salah", "Creating key..." to "Membuat kunci...",
    "Passwords are stored privately on this device." to "Kata sandi disimpan privat di perangkat ini.",
    // notifications
    "Build failed" to "Build gagal", "Tap to view errors" to "Ketuk untuk melihat error",
)

private val RULES = listOf(
    Regex("^Building (.+)$") to "Membangun $1",
    Regex("^Delete (.+)\\?$") to "Hapus $1?",
    Regex("^Missing: (.+)$") to "Belum ada: $1",
    Regex("^(\\d+) errors · (\\d+) warnings$") to "$1 error · $2 peringatan",
    Regex("^Line (\\d+)$") to "Baris $1",
    Regex("^Version (.+)$") to "Versi $1",
    Regex("^(.+) · Build successful · (.+)$") to "$1 · Build berhasil · $2",
    Regex("^(.+) · Build failed · (.+)$") to "$1 · Build gagal · $2",
    Regex("^(.+) · Cancelled · (.+)$") to "$1 · Dibatalkan · $2",
    Regex("^Downloading (\\d+)%$") to "Mengunduh $1%",
    Regex("^Ready to build (.+)$") to "Siap membangun $1",
    Regex("^Downloading Gradle (.+)$") to "Mengunduh Gradle $1",
    Regex("^Extracting Gradle (.+)$") to "Mengekstrak Gradle $1",
    Regex("^Downloading (.+) (\\d+)%$") to "Mengunduh $1 $2%",
    Regex("^Installed: (\\d+)$") to "Terpasang: $1",
    Regex("^(\\d+) projects on this device$") to "$1 proyek di perangkat ini",
    Regex("^Install latest (.+)$") to "Pasang terbaru: $1",
    Regex("^Use Gradle (.+) and build again$") to "Pakai Gradle $1 dan build ulang",
    Regex("^Set Compile SDK to (\\d+) and build again$") to "Ubah Compile SDK ke $1 dan build ulang",
    Regex("^Android API (\\d+) is not available \\(installed up to API (.+)\\)\\. Lower Compile SDK in Configuration\\.$") to "Android API $1 tidak tersedia (terpasang sampai API $2). Turunkan Compile SDK di Konfigurasi.",
    Regex("^Compile SDK (\\d+) is not available in this app's SDK list \\(newest: API (\\d+)\\)\\. The build would stop\\.$") to "Compile SDK $1 tidak tersedia di daftar SDK aplikasi ini (terbaru: API $2). Build akan berhenti.",
    Regex("^Lower Compile SDK to (\\d+) on import$") to "Turunkan Compile SDK ke $1 saat impor",
    Regex("^Keep Compile SDK (\\d+)$") to "Pertahankan Compile SDK $1",
    Regex("^Failed: (.+)$") to "Gagal: $1",
    Regex("^(.+) built successfully$") to "$1 berhasil dibangun",
    Regex("^(.+) is ready$") to "$1 siap",
    Regex("^(.+) is ready to install$") to "$1 siap dipasang",
    Regex("^(.+) ready$") to "$1 siap",
)

/** Translations of the newer modules (Git, GitHub, editor tools, preview, diagnostics). */
private val EXTRA_EXACT: Map<String, String> by lazy {
    GitTexts.exact + GitHubTexts.exact + EditorTexts.exact + PreviewTexts.exact + DiagTexts.exact
}
private val EXTRA_RULES: List<Pair<Regex, String>> by lazy {
    GitTexts.rules + GitHubTexts.rules + EditorTexts.rules + PreviewTexts.rules + DiagTexts.rules
}

fun tr(s: String): String {
    if (!L.indonesian) return s
    ID[s]?.let { return it }
    EXTRA_EXACT[s]?.let { return it }
    for ((r, t) in RULES) if (r.matches(s)) return r.replace(s, t)
    for ((r, t) in EXTRA_RULES) if (r.matches(s)) return r.replace(s, t)
    return s
}
