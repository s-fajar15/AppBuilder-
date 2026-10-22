package dev.appbuilder

/** Indonesian translations for the GitHub client messages and the GitHub screens. Merged into I18n.kt. */
object GitHubTexts {
    val exact: Map<String, String> = mapOf(
        GhMsg.BAD_TOKEN to "GitHub menolak token ini. Buat token baru lalu masuk lagi.",
        GhMsg.FORBIDDEN to "GitHub menolak tindakan ini. Token mungkin kurang izin.",
        GhMsg.NOT_FOUND to "Tidak ditemukan di GitHub. Periksa nama dan akses Anda.",
        GhMsg.NAME_EXISTS to "Repositori dengan nama ini sudah ada",
        GhMsg.VALIDATION to "GitHub tidak bisa menerima permintaan ini",
        GhMsg.NO_CONNECTION to "Tidak ada koneksi ke GitHub. Periksa internet Anda.",
        GhMsg.SERVER to "GitHub sedang bermasalah. Coba lagi nanti.",
        GhMsg.BAD_RESPONSE to "GitHub mengirim respons yang tidak bisa dibaca aplikasi",
        GhMsg.PR_EXISTS to "Pull request untuk branch ini sudah ada",
        GhMsg.PR_NO_DIFF to "Tidak ada perbedaan antara kedua branch",
        GhMsg.RATE_LIMIT to "Batas permintaan GitHub tercapai. Coba lagi nanti.",
        "GitHub" to "GitHub", "Sign in with a token" to "Masuk dengan token", "Personal access token" to "Token akses pribadi",
        "Create token on GitHub" to "Buat token di GitHub", "Sign in" to "Masuk", "Sign out" to "Keluar",
        "Your repositories" to "Repositori Anda", "Search repositories" to "Cari repositori", "Create repository" to "Buat repositori",
        "Repository name" to "Nama repositori", "Description" to "Deskripsi", "Private" to "Privat", "Public" to "Publik",
        "Publish to GitHub" to "Terbitkan ke GitHub", "Open pull request" to "Buat pull request", "Title" to "Judul",
        "Base branch" to "Branch tujuan", "Pull requests" to "Pull request", "Workflow runs" to "Proses workflow",
        "Open in browser" to "Buka di browser", "Token is stored encrypted on this device." to "Token disimpan terenkripsi di perangkat ini.",
        "Token needs the repo permission." to "Token butuh izin repo.", "Not signed in" to "Belum masuk",
        "Sign in to GitHub" to "Masuk ke GitHub", "No repositories found." to "Tidak ada repositori.",
        "Paste the token here" to "Tempel token di sini", "That does not look like a GitHub token" to "Ini tidak terlihat seperti token GitHub",
        "Checking token..." to "Memeriksa token...", "Signed in" to "Sudah masuk", "Fork" to "Fork", "Archived" to "Diarsipkan",
        "Read only" to "Hanya baca", "Draft" to "Draf", "No pull requests." to "Tidak ada pull request.", "No workflow runs." to "Tidak ada proses workflow.",
        "Create the repository and push this project" to "Buat repositori dan push proyek ini", "Pull request created" to "Pull request dibuat",
        "Repository created" to "Repositori dibuat", "Remote added" to "Remote ditambahkan", "Refresh" to "Muat ulang",
        "Public repositories only. Sign in to GitHub in Settings to clone private ones." to "Hanya repositori publik. Masuk ke GitHub di Pengaturan untuk clone yang privat.", "Open in GitHub" to "Buka di GitHub", "Token removed" to "Token dihapus",
    )

    val rules: List<Pair<Regex, String>> = listOf(
        Regex("^GitHub rate limit reached\\. Try again after (.+)\\.$") to "Batas permintaan GitHub tercapai. Coba lagi setelah $1.",
        Regex("^GitHub error \\((\\d+)\\)$") to "Kesalahan GitHub ($1)",
        Regex("^GitHub could not accept this request: (.+)$") to "GitHub tidak bisa menerima permintaan ini: $1",
        Regex("^Signed in as (.+)$") to "Masuk sebagai $1",
        Regex("^Clone (.+)$") to "Clone $1",
        Regex("^Pull request #(\\d+)$") to "Pull request #$1",
        Regex("^Created (.+)$") to "Dibuat $1",
        Regex("^Pushed to (.+)$") to "Berhasil dikirim ke $1",
    )
}
