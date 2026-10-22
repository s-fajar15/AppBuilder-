package dev.appbuilder

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

interface HttpTransport {
    fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResp
}

/** [headers] has lower-cased keys. */
data class HttpResp(val code: Int, val body: String, val headers: Map<String, String>)

object UrlTransport : HttpTransport {
    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResp {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.requestMethod = method
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "AppBuilder")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val h = LinkedHashMap<String, String>()
            c.headerFields.forEach { (k, v) -> if (k != null && v != null && v.isNotEmpty()) h[k.lowercase()] = v.joinToString(", ") }
            return HttpResp(code, text, h)
        } finally { c.disconnect() }
    }
}

/** Every user-facing English message of the GitHub client. Translations live in [GitHubTexts]. */
object GhMsg {
    const val BAD_TOKEN = "GitHub rejected the token. Create a new one and sign in again."
    const val FORBIDDEN = "GitHub refused this action. The token may lack the needed permission."
    const val NOT_FOUND = "Not found on GitHub. Check the name and your access."
    const val NAME_EXISTS = "A repository with this name already exists"
    const val VALIDATION = "GitHub could not accept this request"
    const val NO_CONNECTION = "No connection to GitHub. Check your internet."
    const val SERVER = "GitHub is having problems. Try again later."
    const val BAD_RESPONSE = "GitHub sent a response this app could not read"
    const val PR_EXISTS = "A pull request for these branches already exists"
    const val PR_NO_DIFF = "There are no differences between the two branches"
    const val RATE_LIMIT = "GitHub rate limit reached. Try again later."
    fun rateLimitAt(time: String) = "GitHub rate limit reached. Try again after $time."
    fun other(code: Int) = "GitHub error ($code)"
}

class GitHubException(val status: Int, val msg: String) : Exception(msg)

data class GhUser(val login: String, val name: String?, val avatarUrl: String, val htmlUrl: String)

data class GhRepo(
    val id: Long, val name: String, val fullName: String, val owner: String, val isPrivate: Boolean, val description: String?,
    val htmlUrl: String, val cloneUrl: String, val defaultBranch: String, val updatedAt: String, val fork: Boolean,
    val archived: Boolean, val pushPermission: Boolean,
)

data class GhPull(
    val number: Int, val title: String, val state: String, val htmlUrl: String, val head: String, val base: String,
    val author: String, val draft: Boolean,
)

data class GhBranch(val name: String, val protected: Boolean)

data class GhRun(
    val id: Long, val name: String, val status: String, val conclusion: String?, val branch: String,
    val htmlUrl: String, val createdAt: String,
)

class GitHubApi(
    private val token: String,
    private val http: HttpTransport = UrlTransport,
    private val base: String = "https://api.github.com",
) {
    companion object {
        fun parseRepoUrl(url: String): Pair<String, String>? {
            var s = url.trim().trimEnd('/')
            if (s.endsWith(".git", true)) s = s.dropLast(4)
            val m = Regex("^(?:https?://(?:[^/@]+@)?(?:www\\.)?github\\.com/|git@github\\.com:|ssh://git@github\\.com/|github\\.com/)([\\w.-]+)/([\\w.-]+)$").find(s)
                ?: Regex("^([\\w.-]+)/([\\w.-]+)$").find(s)
                ?: return null
            return m.groupValues[1] to m.groupValues[2]
        }

        fun tokenLooksValid(token: String): Boolean {
            val t = token.trim()
            return Regex("^(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}$").matches(t) ||
                Regex("^github_pat_[A-Za-z0-9_]{20,}$").matches(t) ||
                Regex("^[0-9a-f]{40}$").matches(t)
        }

        fun newTokenUrl(): String = "https://github.com/settings/tokens/new?scopes=repo,workflow&description=AppBuilder"

        fun maskToken(t: String): String {
            val s = t.trim()
            if (s.length < 12) return "…"
            val prefix = s.substringBefore('_', "").let { if (it.isNotEmpty() && it.length <= 10 && s.contains('_')) "${it}_" else "" }
            return prefix + "…" + s.takeLast(4)
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun call(method: String, path: String, body: Any? = null): HttpResp {
        val url = if (path.startsWith("http")) path else base + path
        val headers = linkedMapOf(
            "Authorization" to "Bearer ${token.trim()}",
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
        )
        if (body != null) headers["Content-Type"] = "application/json"
        val resp = try {
            http.request(method, url, headers, body?.let { Json.stringify(it) })
        } catch (e: IOException) {
            throw GitHubException(0, GhMsg.NO_CONNECTION)
        }
        if (resp.code in 200..299) return resp
        throw error(resp)
    }

    private fun error(r: HttpResp): GitHubException {
        val parsed = try { Json.parse(r.body) } catch (_: JsonException) { null }
        val apiMsg = Json.str(parsed, "message").orEmpty()
        val errors = Json.arr(parsed, "errors").orEmpty()
        val errText = errors.joinToString(" ") { (it as? Map<*, *>)?.get("message")?.toString() ?: it.toString() }
        val msg = when {
            r.code == 401 -> GhMsg.BAD_TOKEN
            (r.code == 403 || r.code == 429) && (r.headers["x-ratelimit-remaining"] == "0" || apiMsg.contains("rate limit", true)) -> {
                val reset = r.headers["x-ratelimit-reset"]?.toLongOrNull()
                if (reset != null) GhMsg.rateLimitAt(java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(reset * 1000))) else GhMsg.RATE_LIMIT
            }
            r.code == 403 -> GhMsg.FORBIDDEN
            r.code == 404 -> GhMsg.NOT_FOUND
            r.code == 422 && (errText.contains("name already exists", true) || apiMsg.contains("name already exists", true)) -> GhMsg.NAME_EXISTS
            r.code == 422 && errText.contains("pull request already exists", true) -> GhMsg.PR_EXISTS
            r.code == 422 && errText.contains("No commits between", true) -> GhMsg.PR_NO_DIFF
            r.code == 422 -> GhMsg.VALIDATION + if (errText.isNotBlank()) ": $errText" else ""
            r.code >= 500 -> GhMsg.SERVER
            else -> GhMsg.other(r.code)
        }
        return GitHubException(r.code, msg)
    }

    private fun json(r: HttpResp): Any? = try { Json.parse(r.body) } catch (e: JsonException) { throw GitHubException(r.code, GhMsg.BAD_RESPONSE) }

    private fun nextLink(r: HttpResp): String? =
        Regex("<([^>]+)>;\\s*rel=\"next\"").find(r.headers["link"].orEmpty())?.groupValues?.get(1)

    private fun user(m: Any?) = GhUser(
        login = Json.str(m, "login").orEmpty(), name = Json.str(m, "name"),
        avatarUrl = Json.str(m, "avatar_url").orEmpty(), htmlUrl = Json.str(m, "html_url").orEmpty(),
    )

    private fun repo(m: Any?): GhRepo {
        val perms = Json.obj(m, "permissions")
        return GhRepo(
            id = Json.long(m, "id") ?: 0, name = Json.str(m, "name").orEmpty(), fullName = Json.str(m, "full_name").orEmpty(),
            owner = Json.str(Json.obj(m, "owner"), "login").orEmpty(), isPrivate = Json.bool(m, "private") ?: false,
            description = Json.str(m, "description"), htmlUrl = Json.str(m, "html_url").orEmpty(),
            cloneUrl = Json.str(m, "clone_url").orEmpty(), defaultBranch = Json.str(m, "default_branch") ?: "main",
            updatedAt = Json.str(m, "updated_at").orEmpty(), fork = Json.bool(m, "fork") ?: false,
            archived = Json.bool(m, "archived") ?: false,
            pushPermission = perms?.let { Json.bool(it, "push") ?: Json.bool(it, "admin") } ?: true,
        )
    }

    private fun pull(m: Any?) = GhPull(
        number = Json.int(m, "number") ?: 0, title = Json.str(m, "title").orEmpty(), state = Json.str(m, "state").orEmpty(),
        htmlUrl = Json.str(m, "html_url").orEmpty(), head = Json.str(Json.obj(m, "head"), "ref").orEmpty(),
        base = Json.str(Json.obj(m, "base"), "ref").orEmpty(), author = Json.str(Json.obj(m, "user"), "login").orEmpty(),
        draft = Json.bool(m, "draft") ?: false,
    )

    /** Checks the token and returns the user plus the OAuth scopes GitHub reports (empty for fine-grained tokens). */
    fun validate(): Pair<GhUser, List<String>> {
        val r = call("GET", "/user")
        val scopes = r.headers["x-oauth-scopes"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return user(json(r)) to scopes
    }

    fun user(): GhUser = user(json(call("GET", "/user")))

    fun repos(page: Int = 1, perPage: Int = 50, query: String? = null): List<GhRepo> {
        val r = call("GET", "/user/repos?per_page=$perPage&page=$page&sort=updated&affiliation=owner,collaborator,organization_member")
        val all = (json(r) as? List<*>).orEmpty().map { repo(it) }
        return if (query.isNullOrBlank()) all else all.filter { it.fullName.contains(query.trim(), true) || it.description?.contains(query.trim(), true) == true }
    }

    fun allRepos(maxPages: Int = 5): List<GhRepo> {
        val out = ArrayList<GhRepo>()
        var url: String? = "/user/repos?per_page=100&sort=updated&affiliation=owner,collaborator,organization_member"
        var n = 0
        while (url != null && n++ < maxPages) {
            val r = call("GET", url)
            out += (json(r) as? List<*>).orEmpty().map { repo(it) }
            url = nextLink(r)
        }
        return out
    }

    fun createRepo(name: String, description: String, isPrivate: Boolean, autoInit: Boolean = false): GhRepo {
        val body = linkedMapOf<String, Any?>("name" to name, "private" to isPrivate, "auto_init" to autoInit)
        if (description.isNotBlank()) body["description"] = description
        return repo(json(call("POST", "/user/repos", body)))
    }

    fun branches(fullName: String): List<GhBranch> =
        (json(call("GET", "/repos/$fullName/branches?per_page=100")) as? List<*>).orEmpty()
            .map { GhBranch(Json.str(it, "name").orEmpty(), Json.bool(it, "protected") ?: false) }

    fun createPull(fullName: String, title: String, body: String, head: String, base: String, draft: Boolean = false): GhPull =
        pull(json(call("POST", "/repos/$fullName/pulls", linkedMapOf("title" to title, "body" to body, "head" to head, "base" to base, "draft" to draft))))

    fun pulls(fullName: String, state: String = "open"): List<GhPull> =
        (json(call("GET", "/repos/$fullName/pulls?state=${enc(state)}&per_page=30")) as? List<*>).orEmpty().map { pull(it) }

    fun runs(fullName: String, branch: String? = null, limit: Int = 10): List<GhRun> {
        val b = if (branch.isNullOrBlank()) "" else "&branch=${enc(branch)}"
        val root = json(call("GET", "/repos/$fullName/actions/runs?per_page=$limit$b"))
        return Json.arr(root, "workflow_runs").orEmpty().map {
            GhRun(
                id = Json.long(it, "id") ?: 0, name = Json.str(it, "name").orEmpty(), status = Json.str(it, "status").orEmpty(),
                conclusion = Json.str(it, "conclusion"), branch = Json.str(it, "head_branch").orEmpty(),
                htmlUrl = Json.str(it, "html_url").orEmpty(), createdAt = Json.str(it, "created_at").orEmpty(),
            )
        }
    }
}
