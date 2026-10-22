package dev.appbuilder

/** Indonesian translations for the Compose preview screen and its warnings. Merged into I18n.kt. */
object PreviewTexts {
    val exact: Map<String, String> = mapOf(
        PreviewMsg.TOO_MANY to "Terlalu banyak elemen: pratinjau dipotong",
        "Preview" to "Pratinjau", "Compose preview" to "Pratinjau Compose", "No @Preview functions found" to "Tidak ada fungsi @Preview",
        "Add a @Preview to a @Composable function to see it here." to "Tambahkan @Preview pada fungsi @Composable untuk melihatnya di sini.",
        "Preview is approximate: it shows layout and text, not exact Material styling." to "Pratinjau ini perkiraan: menampilkan tata letak dan teks, bukan gaya Material yang persis.",
        "Light" to "Terang", "Dark" to "Gelap", "Zoom" to "Zoom", "Warnings" to "Peringatan", "Reading project..." to "Membaca proyek...",
        "Image" to "Gambar", "Open file" to "Buka file", "Choose preview" to "Pilih pratinjau", "Not previewable" to "Tidak bisa dipratinjau",
        "Preview from source (no build needed)" to "Pratinjau dari kode (tanpa build)",
    )

    val rules: List<Pair<Regex, String>> = listOf(
        Regex("^Cannot preview (.+)$") to "Tidak bisa menampilkan $1",
        Regex("^Skipped (.+): it has parameters without default values$") to "$1 dilewati: punya parameter tanpa nilai bawaan",
        Regex("^Could not read the code at line (\\d+)$") to "Tidak bisa membaca kode di baris $1",
        Regex("^The condition at line (\\d+) is not constant: showing the first branch$") to "Kondisi di baris $1 tidak konstan: menampilkan cabang pertama",
        Regex("^Using sample data for the list at line (\\d+)$") to "Memakai data contoh untuk daftar di baris $1",
        Regex("^Skipped a recursive call to (.+)$") to "Melewati pemanggilan rekursif ke $1",
        Regex("^String resource (.+) was not found$") to "Resource string $1 tidak ditemukan",
        Regex("^Preview (.+) uses a custom annotation, which is not supported$") to "Preview $1 memakai anotasi khusus yang belum didukung",
        Regex("^(\\d+) previews$") to "$1 pratinjau",
    )
}
