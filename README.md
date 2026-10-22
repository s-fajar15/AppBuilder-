# App Builder

**Bangun APK Android langsung di Android.** App Builder adalah IDE ringan yang berjalan di perangkat Android ARM64: edit kode, kelola dependensi, lalu build APK debug atau release dengan JDK, Gradle, dan Android SDK yang terpasang di dalam app itu sendiri. Tanpa komputer, tanpa Termux.

*English: a lightweight IDE that builds Android projects (APK/AAB) on an Android ARM64 device using its own bundled toolchain.*

## Status

| Area | Status |
|---|---|
| Build APK debug di perangkat ARM64 | Terbukti jalan (beberapa project nyata) |
| Build tanpa Termux terpasang | Terbukti jalan di satu perangkat |
| APK release, AAB, dan signing | Sudah ditulis, **belum diuji luas** |
| Perangkat dan versi Android lain | **Belum diuji** |

Ini rilis awal (0.1). Perilaku bisa berubah.

## Fitur

- **Project:** buat project baru (Kotlin/Java, Compose/XML), impor dan ekspor ZIP, hapus, cari, filter.
- **Editor:** syntax highlighting (Kotlin, Java, XML, Gradle, properties, JSON), nomor baris, auto-indent, auto-close kurung, simpan otomatis, **zoom dengan dua jari**, **pembungkus baris (word wrap)**, bilah simbol.
- **Konfigurasi project:** nama app, application ID, versi, min/target/compile SDK, versi Kotlin dan Gradle, langsung ke file Gradle project.
- **Dependensi:** tambah, ubah, hapus; daftar library populer.
- **Build:** Debug APK, Release APK, Release AAB; log lengkap, error yang bisa diketuk dan membuka file di barisnya, riwayat build, notifikasi dengan progres dan tombol batal.
- **Signing:** buat kunci (memakai `keytool` dari JDK), impor `.jks`/`.p12`, cadangkan kunci. Kunci diterapkan lewat skrip Gradle, file project tidak diubah.
- **Android SDK Manager:** semua Platform, semua Build-Tools, dan semua rilis Gradle stabil. Daftar diambil dari sumber resmi Google dan Gradle, jadi versi baru muncul sendiri. Versi Gradle mengikuti `gradle-wrapper.properties` project; Platform sesuai `compileSdk` dipasang otomatis sebelum build.
- **Lingkungan build:** pasang dari URL atau impor arsip, ekspor paket mandiri, diagnostik yang benar-benar menjalankan `java`, `gradle`, dan `aapt2`.
- **Hasil build:** ikon asli app, pasang, bagikan, simpan ke penyimpanan.
- **Bahasa:** Indonesia dan English. **Tema:** terang, gelap, atau mengikuti sistem.

### Fitur baru (belum diuji di perangkat)
- **Git**: menu proyek > Git — init, clone (tombol "Clone repository" di daftar proyek), status/stage/commit, riwayat, diff, branch, merge, remote, fetch/pull/push, editor `.gitignore`. HTTPS saja (SSH tidak didukung).
- **GitHub**: Pengaturan > GitHub — masuk dengan token (disimpan terenkripsi Android Keystore), daftar repo, clone, publish proyek, pull request, status workflow.
- **Compose Preview**: menu proyek > Preview — membaca `@Preview` dari kode tanpa build; hasilnya perkiraan, komponen tak dikenal jadi placeholder.
- **Diagnostik build**: pemeriksaan sebelum build, memori Gradle otomatis, kartu diagnosis dengan perbaikan satu ketukan, laporan error yang disanitasi.

Tes unit: `./gradlew test`.

## Persyaratan

- Perangkat Android **ARM64**, Android 8.0 (API 26) atau lebih baru.
- Ruang penyimpanan beberapa GB (JDK, Gradle, SDK, cache dependensi).
- Internet untuk build pertama setiap project (unduh dependensi, Platform, Build-Tools).
- **JDK untuk Android (bionic).** JDK Linux desktop (glibc), termasuk build `linux-aarch64` dari Oracle, **tidak bisa berjalan di Android**. App akan menolaknya dengan pesan yang jelas.

JDK 21 untuk Android: <https://github.com/s-fajar15/OpenJDK-21/releases/tag/oepnjdk-21>

## Memulai

1. Pasang APK App Builder dan buka tab **SDK**.
2. **JDK:** ketuk JDK lalu **Install** (mengambil dari rilis di atas), atau **Import archive** untuk arsip Anda sendiri.
3. **Gradle:** ketuk Gradle lalu **Install**. Versi lain diunduh otomatis sesuai project.
4. **Lisensi:** buka *Android SDK license*, baca, lalu setujui.
5. Buat atau impor project, buka, dan ketuk tombol **Run**.

Tombol **Run diagnostics** di tab SDK memeriksa JDK, Gradle, Platform, dan `aapt2` secara langsung dan melaporkan apa yang kurang.

## Build dari source

Butuh JDK 17+ dan Android SDK 36.

```bash
gradle wrapper --gradle-version 8.11.1   # sekali saja
./gradlew assembleDebug
```

Hasil: `app/build/outputs/apk/debug/app-debug.apk`.

Stack: Kotlin, Jetpack Compose (tanpa Material), AGP 8.9.1, Gradle 8.11.1. Font Google Sans dibundel di `app/src/main/res/font`; pastikan lisensinya sesuai sebelum mendistribusikan.

## Struktur

```
app/src/main/java/dev/appbuilder/
  MainActivity.kt      navigasi dan state utama
  Screens.kt           proyek, pengaturan, navigasi
  ProjectScreen.kt     editor, pohon file, hasil build
  Editor.kt            editor kode, zoom, word wrap
  BuildManager.kt      menjalankan Gradle, progres, notifikasi
  Environment.kt       JDK, Gradle, SDK, unduhan, signing, diagnostik
  BuildScreens.kt      layar build, SDK, konfigurasi, dependensi
  Signing.kt           kunci rilis
  Templates.kt         template project baru
docs/                  PRD dan mockup
```

## Keterbatasan yang perlu diketahui

- **Target SDK 28.** Android 10+ melarang menjalankan file dari penyimpanan app untuk target yang lebih baru, sedangkan toolchain harus dieksekusi. Akibatnya app ini untuk sideload, **bukan untuk Play Store**, dan Android baru bisa menampilkan peringatan saat memasangnya.
- **`aapt2` ARM64** harus tersedia di Build-Tools yang diimpor. Build-Tools resmi Google berisi program x86 yang tidak dipakai untuk eksekusi.
- **Satu JDK.** Belum ada pengelola beberapa versi JDK.
- Persentase progres build adalah perkiraan; Gradle tidak mengetahui jumlah total tugasnya.
- Unduhan dari tab SDK belum berjalan lewat service latar belakang; Android bisa menghentikannya saat app ditutup.

## Pemecahan masalah

| Gejala | Penyebab umum |
|---|---|
| `libz.so.1 not found` / `libandroid-shmem.so not found` | Library bawaan JDK build Termux. Gunakan **Run diagnostics**; paket hasil *Export package* membawa library ini sendiri. |
| *Minimum supported Gradle version is X* | Pilih **Pakai Gradle X dan build ulang**, atau perbarui `gradle-wrapper.properties`. |
| *licences have not been accepted* | Setujui lisensi lewat tombol **Review SDK license**. |
| JDK ditolak ("glibc") | Itu JDK Linux desktop. Pakai build Android (bionic). |
| Error Room/KSP di `kspDebugKotlin` | Sudah ditangani oleh environment; kirim log jika masih muncul. |

## Pengembang

**s.fajar** · GitHub: [s-fajar15](https://github.com/s-fajar15)
