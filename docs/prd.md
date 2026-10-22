# App Builder — Product Requirements Document

## 1. Overview

### Product Name

**App Builder**

### Platform

Android

### Product Type

Mobile Android development environment / Android IDE.

### Purpose

App Builder adalah aplikasi Android yang memungkinkan pengguna membuat, mengedit, dan membangun aplikasi Android langsung dari perangkat Android.

Tujuan utama:

```text
Create Project
      ↓
Edit Code
      ↓
Configure Project
      ↓
Build
      ↓
APK / AAB
      ↓
Install / Share
```

App Builder harus dirancang sebagai **Android IDE yang benar-benar dibuat untuk perangkat mobile**, bukan sekadar editor kode atau versi kecil dari desktop IDE.

---

# 2. Important Development Context

## 2.1 How App Builder Is Developed

App Builder sendiri akan **dikembangkan dan di-build menggunakan Termux** selama proses development.

Contoh:

```text
Termux
   ↓
Gradle
   ↓
App Builder APK
```

Termux adalah **development tool saja**.

Termux bukan bagian dari App Builder dan tidak boleh menjadi dependency untuk pengguna akhir.

## 2.2 Final Product

Setelah App Builder selesai dikembangkan, pengguna harus dapat menggunakan App Builder tanpa:

- Termux
- proot-distro
- Debian
- Ubuntu
- aplikasi terminal eksternal
- PC

Target akhirnya:

```text
App Builder
     ↓
Build Environment
     ↓
Android Application
     ↓
APK / AAB
```

---

# 3. Product Vision

App Builder harus terasa seperti:

> **Android IDE yang memang dirancang untuk Android.**

Bukan:

- Android Studio yang diperkecil
- text editor dengan tombol Build
- terminal wrapper
- aplikasi dengan UI desktop yang dipaksa masuk ke layar HP

Prioritas produk:

1. Simpel
2. Modern
3. Cepat
4. Stabil
5. Mobile-first
6. Powerful
7. Mudah digunakan

---

# 4. Design Principles

## 4.1 Modern UI

UI harus terlihat seperti aplikasi Android modern tahun 2026.

Harus memiliki:

- spacing konsisten
- typography jelas
- hierarchy yang kuat
- icon konsisten
- navigation sederhana
- touch target nyaman
- layout mobile-first

## 4.2 No AI Slop

Jangan menggunakan desain yang terlihat seperti hasil template AI.

Hindari:

- terlalu banyak card
- gradient
- blur
- glassmorphism
- glow
- shadow berlebihan
- icon random
- emoji sebagai icon
- dekorasi tidak berguna
- dashboard penuh statistik
- warna terlalu banyak
- oversized button
- pill berlebihan
- ilustrasi generik
- empty state dengan artwork AI

## 4.3 No Gradient

Tidak boleh menggunakan gradient pada:

- background
- button
- card
- header
- icon
- progress
- navigation
- accent

Gunakan warna solid.

## 4.4 No Blur

Tidak boleh menggunakan:

- backdrop blur
- glass blur
- frosted glass
- blurred navigation
- translucent blur effect

## 4.5 No Excessive Cards

Tidak semua element harus menjadi card.

Gunakan:

- spacing
- divider
- typography
- surface
- alignment

untuk membuat hierarchy.

---

## 4.6 No Transparency

Tidak boleh menggunakan warna transparan atau alpha pada surface, background, border, overlay visual, maupun navigation.

Gunakan warna solid untuk semua elemen UI.

---

## 4.7 No Monitoring UI

App Builder harus terasa seperti Android development workspace, bukan aplikasi monitoring, analytics dashboard, atau system monitor.

Jangan tampilkan:

- statistik project (jumlah file, jumlah dependency, ukuran project) sebagai highlight
- status indicator berlebihan, termasuk titik hijau/merah di setiap item
- build health atau project health
- progress, metric, grafik, counter, atau badge yang tidak diperlukan
- informasi "Built X minutes ago" yang menonjol
- status panel atau widget bergaya monitoring

Pertahankan hanya informasi yang diperlukan untuk membuat, mengedit, build, dan mengelola aplikasi.

Status seperti `Build successful` atau `Build failed` tetap boleh ditampilkan, tetapi sebagai teks kontekstual yang sederhana, bukan sebagai indikator bergaya dashboard.

---

# 5. Target Users

### Primary Users

- Android developer yang bekerja dari HP
- developer yang tidak selalu memiliki PC
- developer yang ingin melakukan quick fixes
- developer yang ingin build APK langsung dari Android

### Secondary Users

- pemula Android development
- hobby developer
- mahasiswa
- developer yang ingin membuka project Android existing

---

# 6. Core Workflow

Workflow utama:

```text
Open App
   ↓
Create / Open Project
   ↓
Project Dashboard
   ↓
Edit Code
   ↓
Save
   ↓
Build
   ↓
Build Log
   ↓
Build Result
   ↓
Install / Share APK
```

Workflow harus dapat dilakukan tanpa terminal.

---

# 7. Main Features

App Builder minimal memiliki:

- Project Manager
- Project Creator
- File Explorer
- Code Editor
- Project Configuration
- Dependency Manager
- Build Manager
- Build Log
- Build History
- APK Manager
- SDK Manager
- Build Environment Manager
- Settings

---

# 8. Home / Project Manager

Home screen adalah pusat aplikasi.

Layout:

```text
Projects

                         Search

+ New Project

MyApp
Android Application
Updated 5 minutes ago

Kynox
Android Application
Updated yesterday

GemaIn
Android Application
Updated 3 days ago
```

Project item menampilkan:

- project icon (launcher icon milik project; icon default jika belum ada)
- project name
- package name
- project type
- last modified
- last build status (hanya ditampilkan jika gagal)

Layout project item:

- icon 44dp, rounded 11dp, di sisi kiri
- project name (Section Title) dan package name (monospace, secondary) di tengah
- baris meta berisi bahasa, UI, dan waktu ubah terakhir dalam teks secondary kecil
- `Build failed` ditampilkan sebagai teks biasa di sisi kanan hanya jika build terakhir gagal; project yang berhasil atau belum dibangun tidak menampilkan status
- tanpa titik status, badge, atau counter jumlah project
- tanpa card; item dipisahkan oleh divider 1dp
- aksi utama di bawah daftar: `New project` (tombol solid) dan `Import ZIP` (tombol berbingkai), berdampingan

Jangan memenuhi screen dengan informasi yang tidak diperlukan.

---

# 9. Create Project

User dapat membuat project baru.

## Templates

Minimal:

### Android Application

- Empty Activity
- Empty Compose Activity
- Basic Activity
- XML Activity

### Other

- Android Library
- Kotlin Library

Template harus benar-benar menghasilkan project yang dapat di-build.

---

# 10. Project Setup

Form:

```text
Project Name

Package Name

Language
Kotlin
Java

UI
Jetpack Compose
XML

Minimum SDK
Target SDK
Compile SDK

Location
```

Button:

```text
Create Project
```

Validasi:

- package name valid
- project name valid
- location writable
- required build environment tersedia

---

# 11. Project Dashboard

Setelah project dibuka:

```text
MyApp

Build
Run
More

Recent Files

MainActivity.kt
AndroidManifest.xml
build.gradle.kts

Project

app
gradle
settings.gradle.kts
```

Project information:

```text
Kotlin
Compose
Android 36
```

Jangan menggunakan dashboard yang penuh statistik.

---

# 12. File Explorer

Project explorer:

```text
app
├── src
│   └── main
│       ├── java
│       ├── res
│       └── AndroidManifest.xml
├── build.gradle.kts
└── proguard-rules.pro
```

Actions:

- New File
- New Folder
- Rename
- Delete
- Copy
- Cut
- Paste
- Duplicate
- Move

Delete harus memiliki confirmation.

---

# 13. Code Editor

Code editor adalah fitur utama.

## Supported Files

Minimal:

```text
.kt
.java
.xml
.gradle
.gradle.kts
.properties
.json
.toml
.md
```

## Features

- syntax highlighting
- line numbers
- auto indentation
- bracket matching
- auto closing brackets
- basic autocomplete
- search
- replace
- go to line
- file tabs
- copy
- paste
- cut
- undo
- redo
- select all
- code folding jika memungkinkan

---

# 14. Mobile Code Editor UI

Editor tidak boleh terasa seperti desktop IDE yang diperkecil.

Contoh:

```text
← MainActivity.kt                         ⋮

1  package com.example.app
2
3  import android.os.Bundle
4
5  class MainActivity {
6      ...
7  }
```

Bottom editing toolbar:

```text
{ }   ( )   [ ]   " "   =   :   ;   →
```

Toolbar dapat horizontal scroll.

---

# 15. Code Editor Settings

User dapat mengatur:

- font size
- font family
- line height
- word wrap
- line numbers
- autocomplete
- syntax theme
- tab size

Code editor menggunakan monospace.

---

# 16. Search

Global search harus mendukung:

- filename
- source code
- project files

Search result:

```text
MainActivity.kt
Line 42

build.gradle.kts
Line 18
```

Tap result langsung membuka file dan posisi yang sesuai.

---

# 17. Project Configuration

User dapat melihat dan mengubah:

- application ID
- app name
- version name
- version code
- minimum SDK
- target SDK
- compile SDK
- Kotlin version
- Gradle version
- build types

Perubahan harus divalidasi sebelum diterapkan.

---

# 18. Dependency Manager

User dapat mengelola dependency dari UI.

Contoh:

```text
Dependencies

androidx.core:core-ktx
1.x

androidx.activity:activity-compose
1.x
```

Actions:

```text
+ Add Dependency
```

Input:

```text
Group
Artifact
Version
```

Support:

- add
- remove
- update
- search
- repository management

---

# 19. Build Manager

Build harus dapat dilakukan tanpa terminal.

Menu:

```text
Build

Build Debug APK
Build Release APK
Build AAB
Clean Project
Rebuild Project
```

User tidak perlu mengetik:

```text
./gradlew assembleDebug
```

---

# 20. Build Environment

Pada versi final, App Builder harus memiliki build environment sendiri.

Komponen:

```text
Build Environment

JDK
Gradle
Android SDK
Android Platform
Android Build Tools
AAPT2
Kotlin Compiler
Required Build Components
```

Environment Manager harus dapat:

- install
- update
- remove
- verify
- repair

component.

---

# 21. Development vs Final Build Environment

## Development Phase

App Builder dapat dikembangkan menggunakan:

```text
Termux
Gradle
Android SDK
JDK
```

Tidak masalah jika developer menggunakan Termux.

## Final Product

App Builder tidak boleh membutuhkan Termux.

```text
App Builder
    ↓
Internal Build Environment
    ↓
Gradle
    ↓
Android Build
    ↓
APK
```

Perbedaan ini harus dipertahankan sepanjang development.

---

# 22. SDK Manager

Screen:

```text
SDK Manager
```

### Android Platforms

```text
Android 34
Installed

Android 35
Available

Android 36
Installed
```

### Build Tools

```text
34.x
Installed

35.x
Available

36.x
Installed
```

### Tools

- Platform Tools
- Build Tools
- SDK Tools

User dapat menginstall component dari dalam aplikasi.

---

# 23. Build Screen

Ketika build:

```text
Building MyApp

████████████████░░░░ 78%

:app:compileDebugKotlin
```

Tampilkan:

- build variant
- current task
- progress
- elapsed time
- build status

UI harus tetap responsive.

---

# 24. Build Log

Log harus readable.

Contoh:

```text
BUILD

> Task :app:compileDebugKotlin

e: MainActivity.kt:42
Unresolved reference: foo

BUILD FAILED
```

Error dapat diketuk.

Ketika diketuk:

```text
MainActivity.kt
Line 42
```

Editor membuka line tersebut.

---

# 25. Build Result

Successful:

```text
Build successful

app-debug.apk
12.4 MB

[Install]
[Share]
[Open Folder]
```

Failed:

```text
Build failed

3 errors
12 warnings

[View Errors]
[Open Build Log]
```

---

# 26. Build History

Project menyimpan history.

```text
Build History

Today

✓ Debug
18:22

✕ Release
17:50

Yesterday

✓ Debug
21:04
```

Detail:

- build variant
- date
- duration
- result
- APK
- log

---

# 27. APK Manager

APK detail:

```text
MyApp

Debug APK

Version
1.0

Version Code
1

Size
12.4 MB

Built
18 Oct 2026
```

Actions:

- Install
- Share
- Open
- Delete

---

# 28. Release Signing

Release build mendukung custom keystore.

```text
Release Signing

Debug Signing
Custom Keystore
```

Custom keystore:

```text
Keystore
Alias
Password
Key Password
```

Credential tidak boleh ditampilkan sebagai plaintext.

Credential tidak boleh disimpan secara tidak aman.

---

# 29. Import Project

Support:

```text
Import ZIP
```

Flow:

```text
Select ZIP
   ↓
Analyze
   ↓
Detect Android Project
   ↓
Import
```

Jika valid:

```text
Android project detected

Gradle
Kotlin
Compile SDK

[Import Project]
```

Layar import (valid):

- judul `Android project detected`
- nama file ZIP dan ukuran (monospace)
- ringkasan project: Build, Language, UI, Compile SDK
- field `Name` dan `Location` yang dapat diubah sebelum import
- tombol utama `Import project` di bawah

Entry point: tombol `Import ZIP` di layar Projects.

Jika invalid:

```text
Project cannot be imported.

Missing:
settings.gradle
```

Layar import (invalid):

- judul `Project cannot be imported`
- daftar file yang hilang (monospace), misalnya `settings.gradle.kts` dan `app/build.gradle.kts`
- petunjuk singkat: pilih ZIP yang berisi project Android berbasis Gradle
- aksi: `Choose another ZIP` (utama) dan `Cancel`

---

# 30. Export Project

Support:

```text
Export Project
```

Output:

```text
MyApp.zip
```

Generated files yang tidak diperlukan dapat dikecualikan:

```text
.gradle
build
```

Project hasil export harus tetap dapat dibuka kembali.

---

# 31. APK Install

Setelah build:

```text
Install APK
```

Jika Android membutuhkan permission install unknown apps:

- tampilkan penjelasan
- arahkan user ke setting
- jangan mengubah setting secara diam-diam

---

# 32. Notifications

Build notification:

```text
Building MyApp
Build in progress
```

Success:

```text
MyApp built successfully
```

Failure:

```text
Build failed
Tap to view errors
```

Jangan mengirim notification untuk setiap build task.

---

# 33. Offline

Tanpa internet user tetap dapat:

- membuka project
- edit code
- melihat files
- menggunakan component yang sudah tersedia
- melakukan build jika seluruh dependency tersedia

Internet diperlukan untuk:

- download dependency baru
- download SDK
- update build tools
- download Gradle
- download template

---

# 34. Storage

Project harus disimpan dengan aman.

User dapat memilih project location menggunakan Android file picker.

Jangan bergantung pada path Termux.

Jangan hardcode:

```text
/data/data/com.termux/...
```

App Builder harus memiliki storage abstraction sendiri.

---

# 35. Performance

App Builder harus:

- startup cepat
- tidak scan seluruh project berulang kali
- tidak reload editor tanpa alasan
- tidak menyebabkan UI freeze
- tidak menggunakan memory secara berlebihan
- menghentikan process setelah build selesai
- membersihkan temporary files
- tetap responsive selama build

---

# 36. Architecture

Gunakan architecture modular.

```text
App Builder
│
├── UI
├── Project Manager
├── Project Generator
├── File Manager
├── Code Editor
├── Project Configuration
├── Dependency Manager
├── Build Manager
├── Build Environment
├── SDK Manager
├── APK Manager
├── Storage Manager
└── Settings
```

Build logic tidak boleh berada langsung di UI.

---

# 37. Build Architecture

```text
Build UI
    ↓
Build Manager
    ↓
Build Queue
    ↓
Build Environment
    ↓
Gradle
    ↓
Compiler
    ↓
APK / AAB
    ↓
Build Result
```

Build harus dijalankan di background.

UI hanya menerima status dari Build Manager.

---

# 38. Build Queue

Jika user menjalankan build:

```text
Build requested
      ↓
Check environment
      ↓
Check project
      ↓
Start build
      ↓
Monitor process
      ↓
Collect logs
      ↓
Detect result
      ↓
Show output
```

Jika build sedang berlangsung, user harus diberi informasi bahwa build sedang berjalan.

---

# 39. Error Handling

Error harus actionable.

Jangan hanya:

```text
Something went wrong.
```

Gunakan:

```text
Build failed

Could not resolve dependency:

androidx.activity:activity-compose

[View Error]
[Open Dependency]
[Build Log]
```

---

# 40. First Launch

Screen:

```text
App Builder

Build Android apps directly
from your device.

[Get Started]
```

Setelah itu:

```text
Preparing build environment

JDK
Android SDK
Build Tools

[Setup]
```

---

# 41. Onboarding

Maksimal 3 halaman.

### Page 1

```text
Build anywhere

Create Android applications
directly from your device.
```

### Page 2

```text
Code comfortably

A mobile-first editor
designed for Android.
```

### Page 3

```text
Build your app

Create, build, install,
and share.
```

Button:

```text
Start Building
```

Tidak menggunakan animasi berlebihan.

---

# 42. Navigation

Navigation utama:

```text
Projects
Builds
SDK
Settings
```

Tetapi ketika berada di project editor, navigation harus fokus ke project.

Project editor dapat menggunakan:

```text
Top Bar
File Tabs
Editor
Bottom Editing Tools
```

---

# 43. Appearance

Support:

- Light
- Dark
- System

UI harus konsisten di semua theme.

Tidak menggunakan gradient atau blur.

---

# 44. Color System

Gunakan restrained color palette.

Contoh dark theme:

```text
Background
#101112

Surface
#181A1B

Elevated Surface
#202223

Primary Text
#F2F3F3

Secondary Text
#9A9EA2

Border
#2A2D2F
```

Contoh light theme (tema referensi mockup):

```text
Background
#FFFFFF

Surface
#F4F5F6

Primary Text / Primary Action
#0F1011

Secondary Text
#6B7076

Border
#E4E6E8

Brand Accent
#FF4F00

Success
#12805C

Error
#D92D20
```

Aturan accent:

- Brand accent (#FF4F00) hanya untuk logo, indikator tab aktif, indikator navigation aktif, dan kursor editor.
- Tombol utama menggunakan warna ink solid (#0F1011 di light theme), bukan warna accent.
- Warna success dan error tidak dipakai sebagai titik atau badge status; hasil build cukup ditulis sebagai teks.
- Semua warna solid. Tidak ada gradient, blur, atau transparansi.

Gunakan accent color hanya untuk action dan status penting.

Semantic colors:

- success
- warning
- error
- info

Tidak menggunakan warna berlebihan.

---

# 45. Typography

Gunakan typography Android modern.

Font:

- UI: Google Sans bervariasi ada bold, medium, reguler 
- Code: Google Sans Code Semi bold

Catatan: Google Sans adalah font proprietary dan tidak tersedia di Google Fonts. Pastikan lisensi dan distribusi font sudah sesuai sebelum dibundel ke aplikasi.

Hierarchy:

```text
Screen Title
Section Title
Body
Secondary
Caption
Code
```

Jangan menggunakan terlalu banyak font size.

Code editor menggunakan monospace.

---

# 46. Icons

Gunakan satu icon family.

Rules:

- konsisten
- sederhana
- 20–24dp
- mudah dikenali
- tidak dekoratif

Jangan menggunakan emoji sebagai icon.

Spesifikasi referensi:

- stroke icon 1.7dp, ujung dan sambungan rounded
- ukuran 20-24dp
- icon project (launcher) 44dp, rounded 11dp, satu bentuk sederhana di atas warna solid

---

# 47. Responsive Design

Support:

- phone
- tablet
- foldable
- landscape

Phone:

```text
File Explorer
     ↓
Editor
```

Tablet:

```text
┌─────────────┬───────────────────────┐
│ File        │ Editor                │
│ Explorer    │                       │
│             │                       │
└─────────────┴───────────────────────┘
```

Layout harus beradaptasi berdasarkan available width.

---

# 48. Hardware Keyboard

Support shortcut:

```text
Ctrl + S
Ctrl + F
Ctrl + H
Ctrl + Z
Ctrl + Shift + Z
Ctrl + C
Ctrl + V
Ctrl + X
Ctrl + A
Ctrl + P
```

Aplikasi tetap harus nyaman tanpa keyboard fisik.

---

# 49. Accessibility

Support:

- content description
- screen reader
- readable text
- sufficient contrast
- minimum touch target
- keyboard navigation

---

# 50. Autosave

Editor harus mendukung autosave.

Autosave tidak boleh menyebabkan:

- file corruption
- editor freeze
- data loss

Jika diperlukan gunakan temporary file/recovery state.

---

# 51. Crash Recovery

Jika App Builder ditutup ketika build berjalan:

Pada startup:

```text
An unfinished build was detected.

[View Build]
[Dismiss]
```

Project source tidak boleh rusak.

---

# 52. Security

Keystore credential harus aman.

Jangan:

- menyimpan password plaintext
- menampilkan password
- memasukkan credential ke log

Project files harus diperlakukan sebagai user data.

---

# 53. MVP

MVP harus fokus pada:

## Project

- Create
- Open
- Import ZIP
- Export ZIP

## Editor

- Kotlin
- Java
- XML
- Gradle
- syntax highlighting
- search
- basic autocomplete

## Build

- Debug APK
- Build log
- Build error
- APK output

## Environment

- Build environment manager
- JDK
- Android SDK
- Gradle
- Build Tools

## APK

- Install
- Share
- Open output

---

# 54. Post-MVP

Setelah MVP stabil:

- Release APK
- AAB
- signing manager
- dependency search
- advanced autocomplete
- code navigation
- go to definition
- refactoring
- Git
- GitHub
- Logcat
- debugger
- device manager
- plugin system

---

# 55. Development Phases

## Phase 1 — UI & Project System

Implement:

- project manager
- create project
- project dashboard
- file explorer
- code editor
- settings
- themes

Goal:

User dapat membuat dan mengedit project.

---

## Phase 2 — Project Engine

Implement:

- project parser
- project configuration
- Gradle configuration
- dependency handling
- project import/export

Goal:

App Builder memahami project Android secara nyata.

---

## Phase 3 — Build Integration

Implement:

- Build Manager
- build queue
- Gradle execution
- build log
- error parser
- APK output

Goal:

User dapat melakukan real build melalui UI.

---

## Phase 4 — Build Environment

Implement:

- JDK management
- Android SDK management
- Build Tools
- Gradle management
- environment verification
- environment repair

Goal:

App Builder mulai memiliki build environment sendiri.

---

## Phase 5 — Standalone Build

Hilangkan kebutuhan terhadap external terminal.

Target:

```text
App Builder
     ↓
Internal Build Environment
     ↓
Gradle
     ↓
Android Build
     ↓
APK
```

Termux tidak boleh diperlukan oleh user.

---

# 56. Development Rules

AI coding agent WAJIB:

1. App Builder sendiri boleh di-build menggunakan Termux selama development.
2. Jangan menjadikan Termux dependency App Builder.
3. Jangan hardcode path Termux.
4. Jangan mengasumsikan root.
5. Jangan mengasumsikan PC.
6. Jangan menghapus fitur yang sudah bekerja.
7. Jangan membuat fake build system.
8. Build harus benar-benar menghasilkan APK.
9. Jangan memblokir UI ketika build berjalan.
10. Semua error harus dapat ditampilkan.
11. Jangan menambahkan dependency yang tidak diperlukan.
12. Jangan membuat placeholder sebagai final implementation.
13. Jangan menggunakan gradient.
14. Jangan menggunakan blur.
15. Jangan menggunakan glassmorphism.
16. Jangan menggunakan emoji sebagai icon.
17. Jangan membuat terlalu banyak card.
18. Jangan membuat UI seperti Android lama.
19. Jangan membuat UI seperti desktop IDE yang diperkecil.
20. Jangan mengorbankan usability demi visual.
21. Jangan menggunakan warna transparan atau alpha pada elemen UI.

---

# 57. UI Acceptance Criteria

UI ditolak jika:

- terlihat jadul
- terlihat seperti aplikasi Android lama
- terlihat seperti desktop IDE
- menggunakan gradient
- menggunakan blur
- menggunakan transparansi
- menggunakan glassmorphism
- menggunakan shadow berlebihan
- terlalu banyak card
- terlalu banyak pill
- menggunakan emoji
- menggunakan icon random
- terlalu banyak warna
- terlalu banyak dekorasi
- terlihat seperti AI-generated template
- spacing tidak konsisten
- typography buruk

UI diterima jika:

- modern
- clean
- professional
- mobile-first
- hierarchy jelas
- spacing konsisten
- typography jelas
- icon konsisten
- warna restrained
- cepat dipahami
- nyaman digunakan satu tangan
- tidak terasa seperti UI jadul
- tidak menggunakan gradient
- tidak menggunakan blur
- tidak menggunakan dekorasi yang tidak diperlukan

---

# 58. Definition of Done

MVP dianggap selesai ketika user dapat:

```text
Open App Builder
       ↓
Create Project
       ↓
Choose Kotlin / Java
       ↓
Choose Compose / XML
       ↓
Create
       ↓
Open MainActivity
       ↓
Edit Code
       ↓
Save
       ↓
Build Debug APK
       ↓
View Build Progress
       ↓
View Build Log
       ↓
Build Successful
       ↓
Open APK
       ↓
Install APK
```

Semua workflow dilakukan melalui UI App Builder.

---

# 59. Final Product Goal

App Builder harus memungkinkan pengguna berkata:

> "Saya cuma punya HP, tapi saya tetap bisa membuat dan build aplikasi Android."

Target akhir:

```text
                    APP BUILDER

        ┌────────────────────────────┐
        │        Project Manager     │
        ├────────────────────────────┤
        │        Code Editor         │
        ├────────────────────────────┤
        │        File Explorer       │
        ├────────────────────────────┤
        │        Build Manager       │
        ├────────────────────────────┤
        │        SDK Manager         │
        ├────────────────────────────┤
        │      Build Environment     │
        └──────────────┬─────────────┘
                       ↓
                  APK / AAB
```

App Builder sendiri **dikembangkan menggunakan Termux**, tetapi **produk final tidak bergantung pada Termux**.

Prioritas utama:

**Create → Code → Build → Install**

Buat fitur inti benar-benar bekerja terlebih dahulu sebelum menambahkan fitur IDE tingkat lanjut.

Jangan mengklaim fitur selesai sebelum fitur tersebut benar-benar dapat digunakan pada perangkat Android nyata.

---

# 60. Brand Identity

## Logo

Logo berupa tiga anak tangga naik dalam satu bentuk solid putih di atas tile oranye (#FF4F00) dengan sudut rounded.

Makna: build yang naik tahap demi tahap.

Aturan:

- hanya warna solid, tanpa gradient, blur, atau transparansi
- tile rounded 25% dari lebar
- versi tile oranye dengan bentuk putih
- versi tile putih dengan bentuk hitam untuk background gelap
- wordmark `App Builder` menggunakan Google Sans, weight 600, letter spacing rapat
- ukuran minimum logo 24dp

## Launcher Icon

Launcher icon menggunakan tile oranye dengan bentuk tangga putih.

---

# 61. UI Reference

Mockup referensi: `mockup.png`.

Layar yang tercakup:

- Projects (Home) dengan icon per project, tombol New project dan Import ZIP
- Import project: valid (Android project detected) dan invalid (Project cannot be imported)
- Code Editor dengan file tabs, line numbers, dan bottom editing toolbar (tanpa status bar)
- Build Result berisi judul hasil, jenis APK, identitas aplikasi yang dibangun (icon, nama, package name, versi, ukuran), lokasi file, link log, serta aksi Install dan Share (tanpa daftar langkah, durasi, atau metric)
- Logo (light dan dark)

Catatan: `mockup.png` dirender dengan font pengganti (Poppins dan DejaVu Sans Mono) karena Google Sans tidak tersedia di lingkungan render. Tampilan final harus menggunakan Google Sans sesuai Section 45.
