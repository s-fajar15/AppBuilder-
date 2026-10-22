# Changelog

Mengikuti format [Keep a Changelog](https://keepachangelog.com/id-ID/1.1.0/) dan [Semantic Versioning](https://semver.org/lang/id/).

## [Unreleased]

### Ditambahkan (fitur baru)
- **Git** (JGit, HTTPS + token): init repository, clone, status, stage/unstage, commit, riwayat, diff, branch (buat/pindah/hapus/merge), remote, fetch, pull, push, dan editor `.gitignore`.
- **Integrasi GitHub**: masuk dengan token, daftar/cari repositori, clone, buat repositori, pull request, dan status workflow (klien REST sendiri, bisa dites tanpa jaringan).
- **Editor kode**: indentasi otomatis (kurung, Enter, Tab / Shift+Tab), pindah ke baris, undo/redo, cari & ganti (semua / satu per satu, peka huruf besar-kecil).
- **Compose Preview**: membaca fungsi `@Preview` dan menampilkannya dari subset DSL Compose; komponen yang tidak dikenal tampil sebagai placeholder dengan peringatan.
- **Diagnostik build**: pemeriksaan sebelum build, memori Gradle (`-Xmx` 768–3072 MB sesuai RAM) dengan deteksi OOM, mesin perbaikan cepat terpadu satu kartu (maks. 2 tombol), pasangan Gradle/AGP yang dipelajari dari error, laporan error yang disanitasi (salin/bagikan), log: hanya error, cari, fase dengan durasi.
- Tes unit JVM untuk semua logika murni (Git, GitHub, JSON, editor, preview, toolchain, diagnostik).

### Ditambahkan
- Pemilihan Gradle otomatis: pilihan pengguna, lalu `gradle-wrapper.properties` (dinaikkan bila Android Gradle Plugin butuh versi lebih baru), lalu versi minimum dari AGP. Sumber pilihan ditulis di baris pertama log build.
- Build berhenti dengan alasan jelas bila `compileSdk` proyek tidak ada di repositori Google, dengan tombol "Ubah Compile SDK ke N dan build ulang".
- Layar Impor memperingatkan bila Compile SDK proyek lebih baru dari yang tersedia, dan menawarkan menurunkannya saat impor.
- Editor: zoom dengan dua jari, tombol A− / A+, dan pembungkus baris (word wrap) yang bisa dinyalakan.
- Editor: nomor baris mengikuti hasil pembungkusan, sehingga tetap sejajar.
- Layar Konfigurasi didesain ulang dalam kartu (Aplikasi, Versi, SDK, Toolchain).
- README dan CHANGELOG.

- Tombol Run kini membuka layar build dengan pilihan **Debug / Release APK / Release AAB** lebih dulu; build baru mulai setelah Anda mengetuk tombol build.
- Setiap build menyimpan lognya sendiri (30 terbaru). Tab Build membuka log tiap build, termasuk yang gagal, lengkap dengan daftar error yang bisa diketuk dan tombol simpan log.

### Diubah
- Editor selalu tampil dalam tema gelap, termasuk ikon status bar.
- Tab file berbentuk chip; tombol simbol lebih besar.
- Ukuran font editor 9 sampai 28.

## [0.1.0] - 2026-10-20

Rilis awal.

### Ditambahkan
- **Project:** buat (Kotlin/Java, Compose/XML), impor dan ekspor ZIP, cari, filter, hapus.
- **Editor:** syntax highlighting, nomor baris, auto-indent, auto-close, simpan otomatis atomik, pohon file, buat/ganti nama/hapus file.
- **Build:** Debug APK, Release APK, Release AAB di perangkat ARM64 lewat Gradle yang berjalan di dalam app; log, error yang bisa diketuk, riwayat, pemulihan setelah app terhenti.
- **Notifikasi:** progres build dan unduhan dengan tombol batal.
- **Signing:** buat, impor, cadangkan, hapus kunci rilis.
- **Android SDK Manager:** Platform, Build-Tools, dan Gradle dalam banyak versi; Install latest; Platform sesuai `compileSdk` dipasang otomatis; Gradle mengikuti project.
- **Lingkungan build:** JDK dari rilis GitHub atau arsip, unduh dari URL dan katalog, ekspor paket mandiri, diagnostik yang menjalankan toolchain.
- **Konfigurasi dan dependensi:** edit file Gradle project, daftar library populer.
- **Antarmuka:** desain baru (kartu, navigasi melayang, aksen oranye), ikon baru, layar Tentang, bahasa Indonesia dan English, tema terang/gelap.
- Hasil build menampilkan ikon asli app; pasang, bagikan, simpan ke penyimpanan.

### Diperbaiki
- Crash saat build gagal cepat (service latar belakang dihentikan sebelum sempat mulai).
- `libz.so.1`: SONAME yang tidak cocok dengan library sistem.
- Library bawaan Termux yang tidak terbaca app lain (`libandroid-shmem.so`).
- Gradle gagal memulai daemon (izin eksekusi `jspawnhelper`, pengaturan JVM).
- Error Room/KSP karena `sqlite-jdbc` salah mendeteksi Android.
- Versi Gradle yang diminta project diabaikan (mis. AGP 9 butuh Gradle 9.5).

### Keamanan
- JDK Linux desktop (glibc) ditolak sebelum dipasang; JDK yang sedang berfungsi tidak pernah tertimpa.
- Persetujuan lisensi Android SDK selalu lewat tindakan eksplisit pengguna.

### Diketahui
- Target SDK 28 (tidak untuk Play Store).
- Release APK, AAB, dan signing belum diuji luas.
- Baru diuji pada satu perangkat ARM64.
