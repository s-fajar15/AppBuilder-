package dev.appbuilder

/** Indonesian translations for the editor's find, replace and go-to-line UI. Merged into I18n.kt. */
object EditorTexts {
    val exact: Map<String, String> = mapOf(
        EditMsg.INVALID_PATTERN to "Pola tidak valid", EditMsg.TOO_SLOW to "Pola terlalu lambat untuk file ini",
        "Undo" to "Urungkan", "Redo" to "Ulangi", "Find" to "Cari", "Replace" to "Ganti", "Replace all" to "Ganti semua",
        "Find and replace" to "Cari dan ganti", "Go to line" to "Ke baris", "Line number" to "Nomor baris", "Match case" to "Cocokkan huruf besar/kecil",
        "Whole word" to "Kata utuh", "Regex" to "Regex", "No matches" to "Tidak ada hasil", "Next" to "Berikutnya", "Previous" to "Sebelumnya",
        "Go" to "Pergi", "Close" to "Tutup", "Replace with" to "Ganti dengan", "Find in file" to "Cari di file", "Line or line:column" to "Baris atau baris:kolom",
        "Enter a line number" to "Masukkan nomor baris", "Indent" to "Geser kanan", "Outdent" to "Geser kiri",
    )

    val rules: List<Pair<Regex, String>> = listOf(
        Regex("^(\\d+) of (\\d+)$") to "$1 dari $2",
        Regex("^Line (\\d+) of (\\d+)$") to "Baris $1 dari $2",
        Regex("^Line 1 to (\\d+)$") to "Baris 1 sampai $1",
        Regex("^Replaced (\\d+)$") to "Mengganti $1",
        Regex("^(\\d+) matches$") to "$1 hasil",
    )
}
