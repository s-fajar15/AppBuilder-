package dev.appbuilder

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

private class Fake(val replies: MutableList<HttpResp>) : HttpTransport {
    val calls = ArrayList<List<String?>>()
    val headers = ArrayList<Map<String, String>>()
    var fail: IOException? = null
    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResp {
        calls += listOf(method, url, body); this.headers += headers
        fail?.let { throw it }
        return replies.removeAt(0)
    }
}

private fun ok(body: String, h: Map<String, String> = emptyMap()) = HttpResp(200, body, h)

private const val TOKEN = "ghp_abcdefghijklmnopqrstuvwxyz0123456789"

private const val REPO_JSON = """{"id":42,"name":"Pixly","full_name":"ann/Pixly","private":true,"description":"Gallery","html_url":"https://github.com/ann/Pixly",
"clone_url":"https://github.com/ann/Pixly.git","default_branch":"main","updated_at":"2026-10-01T10:00:00Z","fork":false,"archived":false,
"owner":{"login":"ann"},"permissions":{"admin":true,"push":true,"pull":true}}"""

class GitHubTest {
    private fun api(vararg r: HttpResp): Pair<GitHubApi, Fake> { val f = Fake(r.toMutableList()); return GitHubApi(TOKEN, f) to f }

    @Test fun validateReadsUserAndScopes() {
        val (a, f) = api(ok("""{"login":"ann","name":"Ann","avatar_url":"https://a/1","html_url":"https://github.com/ann"}""", mapOf("x-oauth-scopes" to "repo, workflow")))
        val (u, scopes) = a.validate()
        assertEquals("ann", u.login); assertEquals(listOf("repo", "workflow"), scopes)
        assertEquals("https://api.github.com/user", f.calls[0][1])
        assertEquals("Bearer $TOKEN", f.headers[0]["Authorization"])
        assertEquals("application/vnd.github+json", f.headers[0]["Accept"])
    }

    @Test fun reposParsingAndPagination() {
        val p1 = ok("[$REPO_JSON]", mapOf("link" to "<https://api.github.com/user/repos?per_page=100&page=2>; rel=\"next\", <https://api.github.com/user/repos?page=3>; rel=\"last\""))
        val p2 = ok("[" + REPO_JSON.replace("\"id\":42", "\"id\":43").replace("Pixly", "Two") + "]")
        val (a, f) = api(p1, p2)
        val all = a.allRepos()
        assertEquals(listOf(42L, 43L), all.map { it.id })
        assertEquals("ann/Pixly", all[0].fullName); assertTrue(all[0].isPrivate); assertTrue(all[0].pushPermission)
        assertEquals("https://api.github.com/user/repos?per_page=100&page=2", f.calls[1][1])
    }

    @Test fun reposQueryFilters() {
        val (a, _) = api(ok("[$REPO_JSON]"))
        assertEquals(1, a.repos(query = "pix").size)
        val (b, _) = api(ok("[$REPO_JSON]"))
        assertEquals(0, b.repos(query = "zzz").size)
    }

    @Test fun createRepoBody() {
        val (a, f) = api(HttpResp(201, REPO_JSON, emptyMap()))
        val r = a.createRepo("Pixly", "Gallery", true)
        assertEquals("POST", f.calls[0][0]); assertEquals("https://api.github.com/user/repos", f.calls[0][1])
        val body = Json.parse(f.calls[0][2]!!)
        assertEquals("Pixly", Json.str(body, "name")); assertEquals(true, Json.bool(body, "private")); assertEquals("Gallery", Json.str(body, "description"))
        assertEquals("https://github.com/ann/Pixly.git", r.cloneUrl)
    }

    @Test fun createPullAndPulls() {
        val pr = """{"number":7,"title":"T","state":"open","html_url":"https://github.com/ann/Pixly/pull/7","head":{"ref":"feat"},"base":{"ref":"main"},"user":{"login":"ann"},"draft":false}"""
        val (a, f) = api(HttpResp(201, pr, emptyMap()), ok("[$pr]"))
        val p = a.createPull("ann/Pixly", "T", "body", "feat", "main")
        assertEquals(7, p.number); assertEquals("feat", p.head)
        assertEquals("https://api.github.com/repos/ann/Pixly/pulls", f.calls[0][1])
        assertEquals(1, a.pulls("ann/Pixly").size)
        assertTrue(f.calls[1][1]!!.contains("state=open"))
    }

    @Test fun runsAndBranches() {
        val run = """{"workflow_runs":[{"id":9,"name":"CI","status":"completed","conclusion":"success","head_branch":"main","html_url":"https://x","created_at":"2026-10-01T00:00:00Z"}]}"""
        val (a, f) = api(ok(run), ok("""[{"name":"main","protected":true},{"name":"dev","protected":false}]"""))
        assertEquals("success", a.runs("ann/Pixly", "main").single().conclusion)
        assertTrue(f.calls[0][1]!!.contains("branch=main"))
        assertEquals(listOf("main", "dev"), a.branches("ann/Pixly").map { it.name })
    }

    private fun failure(resp: HttpResp): GitHubException {
        val (a, _) = api(resp)
        try { a.user(); fail("no exception") } catch (e: GitHubException) { return e }
        throw AssertionError()
    }

    @Test fun errorMapping() {
        assertEquals(GhMsg.BAD_TOKEN, failure(HttpResp(401, """{"message":"Bad credentials"}""", emptyMap())).msg)
        assertTrue(failure(HttpResp(403, """{"message":"API rate limit exceeded"}""", mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "1760000000"))).msg.startsWith("GitHub rate limit reached"))
        assertEquals(GhMsg.FORBIDDEN, failure(HttpResp(403, """{"message":"Resource not accessible"}""", emptyMap())).msg)
        assertEquals(GhMsg.NOT_FOUND, failure(HttpResp(404, """{"message":"Not Found"}""", emptyMap())).msg)
        assertEquals(GhMsg.NAME_EXISTS, failure(HttpResp(422, """{"message":"Repository creation failed.","errors":[{"resource":"Repository","code":"custom","field":"name","message":"name already exists on this account"}]}""", emptyMap())).msg)
        assertEquals(GhMsg.SERVER, failure(HttpResp(502, "<html>bad gateway</html>", emptyMap())).msg)
        assertEquals(GhMsg.other(418), failure(HttpResp(418, "", emptyMap())).msg)
    }

    @Test fun networkAndBadJson() {
        val f = Fake(mutableListOf()); f.fail = IOException("boom ghp_secret")
        try { GitHubApi(TOKEN, f).user(); fail() } catch (e: GitHubException) { assertEquals(GhMsg.NO_CONNECTION, e.msg); assertEquals(0, e.status) }
        val (a, _) = api(ok("not json"))
        try { a.user(); fail() } catch (e: GitHubException) { assertEquals(GhMsg.BAD_RESPONSE, e.msg) }
    }

    @Test fun tokenNeverLeaksInMessages() {
        val all = listOf(401, 403, 404, 422, 500, 418).map { failure(HttpResp(it, """{"message":"x $TOKEN"}""", emptyMap())) }
        for (e in all) { assertFalse(e.msg.contains(TOKEN)); assertFalse(e.toString().contains(TOKEN)) }
    }

    @Test fun helpers() {
        assertEquals("u" to "r", GitHubApi.parseRepoUrl("https://github.com/u/r.git"))
        assertEquals("u" to "r", GitHubApi.parseRepoUrl("git@github.com:u/r.git"))
        assertEquals("u" to "r", GitHubApi.parseRepoUrl("github.com/u/r/"))
        assertEquals("u" to "r", GitHubApi.parseRepoUrl("u/r"))
        assertNull(GitHubApi.parseRepoUrl("https://example.com/u/r"))
        assertTrue(GitHubApi.tokenLooksValid(" $TOKEN ")); assertTrue(GitHubApi.tokenLooksValid("github_pat_" + "A".repeat(30)))
        assertFalse(GitHubApi.tokenLooksValid("hello")); assertFalse(GitHubApi.tokenLooksValid(""))
        assertEquals("ghp_…6789", GitHubApi.maskToken(TOKEN)); assertFalse(GitHubApi.maskToken(TOKEN).contains("abcdefgh"))
        assertTrue(GitHubApi.newTokenUrl().contains("scopes=repo"))
    }

    @Test fun everyMessageIsTranslated() {
        val consts = GhMsg::class.java.declaredFields.filter { it.type == String::class.java }.map { it.also { f -> f.isAccessible = true }.get(null) as String }
        val samples = consts + listOf(GhMsg.rateLimitAt("10:00"), GhMsg.other(418), GhMsg.VALIDATION + ": x")
        for (s in samples) assertTrue("untranslated: $s", GitHubTexts.exact.containsKey(s) || GitHubTexts.rules.any { it.first.matches(s) })
        for ((re, t) in GitHubTexts.rules) {
            val n = re.toPattern().matcher("").groupCount()
            assertTrue(re.pattern, Regex("\\$(\\d)").findAll(t).all { it.groupValues[1].toInt() in 1..n })
        }
    }
}
