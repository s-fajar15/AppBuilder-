package dev.appbuilder

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun open(ctx: android.content.Context, url: String) =
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

private fun errText(e: Exception) = when (e) {
    is GitHubException -> e.msg
    is GitException -> e.msg
    else -> GitMsg.generic(e.message ?: e.toString())
}

/** Clone any HTTPS repository into a new project. */
@Composable
fun CloneScreen(repo: ProjectRepo, initialUrl: String = "", onBack: () -> Unit, onCloned: (Project) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val session = remember { GitHubSession(ctx) }
    var url by remember { mutableStateOf(initialUrl) }
    var name by remember { mutableStateOf(if (initialUrl.isNotBlank()) GitRepo.repoNameFromUrl(initialUrl) else "") }
    var touched by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val nameErr = if (name.isBlank()) null else repo.validateName(name.trim())
    Column(Modifier.fillMaxSize()) {
        TopBar("Clone repository", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Field("Repository URL", url, { url = it; if (!touched) name = GitRepo.repoNameFromUrl(it) }, mono = true)
            Field("Folder name", name, { name = it; touched = true }, error = nameErr)
            if (!session.signedIn) Txt("Public repositories only. Sign in to GitHub in Settings to clone private ones.", T.secondary, p.secondary)
            busy?.let { Txt(it, T.secondary, p.secondary) }
            error?.let { Txt(it, T.secondary, p.error) }
        }
        Box(Modifier.padding(16.dp)) {
            Btn("Clone", {
                error = null; busy = "Cloning..."
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        try { val dir = java.io.File(repo.root, name.trim()); GitRepo.clone(url.trim(), dir, session.auth()) { m, _ -> busy = m }; Result.success(repo.adopt(dir)) }
                        catch (e: Exception) { Result.failure(e) }
                    }
                    busy = null
                    res.onSuccess { onCloned(it) }.onFailure { error = errText(it as Exception) }
                }
            }, Modifier.fillMaxWidth(), accent = true, enabled = busy == null && url.isNotBlank() && name.isNotBlank() && nameErr == null, icon = I.download)
        }
    }
}

/** GitHub account, repositories, and (when a project is given) publishing, pull requests and workflow runs for it. */
@Composable
fun GitHubScreen(repo: ProjectRepo, project: Project?, onBack: () -> Unit, onCloned: (Project) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val session = remember { GitHubSession(ctx) }
    var signed by remember { mutableStateOf(session.signedIn) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<GhRepo?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var publish by remember { mutableStateOf(false) }
    var pr by remember { mutableStateOf(false) }

    fun toast(m: String) = Toast.makeText(ctx, tr(m), Toast.LENGTH_LONG).show()
    val repos by produceState<List<GhRepo>?>(null, signed, tick) {
        value = if (!signed) null else withContext(Dispatchers.IO) {
            try { session.api()!!.allRepos() } catch (e: Exception) { error = errText(e); emptyList() }
        }
    }
    val remote = remember(project, tick) { project?.let { runCatching { GitRepo.open(it.dir).remotes().firstOrNull()?.url?.let(GitHubApi::parseRepoUrl) }.getOrNull() } }
    val runs by produceState<List<GhRun>?>(null, signed, remote, tick) {
        value = if (!signed || remote == null) null else withContext(Dispatchers.IO) { runCatching { session.api()!!.runs("${remote.first}/${remote.second}") }.getOrDefault(emptyList()) }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("GitHub", session.login?.let { "Signed in as $it" }, onBack = onBack, actions = {
            if (signed) IconBtn(I.close, "Sign out", { session.clear(); signed = false; toast("Token removed") })
        })
        if (!signed) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Txt("Sign in to GitHub", T.title.copy(fontSize = 26.sp))
                Txt("Token needs the repo permission.", T.body, p.secondary)
                Btn("Create token on GitHub", { open(ctx, GitHubApi.newTokenUrl()) }, Modifier.fillMaxWidth(), solid = false)
                Field("Personal access token", token, { token = it.trim() }, mono = true)
                Txt("Token is stored encrypted on this device.", T.secondary, p.secondary)
                busy?.let { Txt(it, T.secondary, p.secondary) }
                error?.let { Txt(it, T.secondary, p.error) }
            }
            Box(Modifier.padding(16.dp)) {
                Btn("Sign in", {
                    if (!GitHubApi.tokenLooksValid(token)) { error = "That does not look like a GitHub token"; return@Btn }
                    error = null; busy = "Checking token..."
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { try { Result.success(GitHubApi(token).validate()) } catch (e: Exception) { Result.failure(e) } }
                        busy = null
                        r.onSuccess { (u, scopes) ->
                            session.save(token, u.login); token = ""; signed = true
                            if (scopes.isNotEmpty() && "repo" !in scopes) toast("Token needs the repo permission.")
                        }.onFailure { error = errText(it as Exception) }
                    }
                }, Modifier.fillMaxWidth(), accent = true, enabled = busy == null && token.isNotBlank())
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (project != null) {
                    Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Txt(project.name, T.section)
                        if (remote == null) {
                            Btn("Publish to GitHub", { publish = true }, Modifier.fillMaxWidth(), accent = true)
                        } else {
                            Txt("${remote.first}/${remote.second}", T.code, p.secondary)
                            Btn("Open pull request", { pr = true }, Modifier.fillMaxWidth(), solid = false)
                            Btn("Open in GitHub", { open(ctx, "https://github.com/${remote.first}/${remote.second}") }, Modifier.fillMaxWidth(), solid = false)
                        }
                    }
                    runs?.let { rs ->
                        Txt("Workflow runs", T.secondary, p.secondary)
                        if (rs.isEmpty()) Txt("No workflow runs.", T.body, p.secondary)
                        rs.forEach { r ->
                            Row(Modifier.fillMaxWidth().card().tap({ open(ctx, r.htmlUrl) }).padding(14.dp)) {
                                Column(Modifier.weight(1f)) { Txt(r.name, T.body, maxLines = 1); Txt("${r.branch} · ${timeAgo(parseIso(r.createdAt))}", T.code.copy(fontSize = 11.sp), p.secondary) }
                                Pill(r.conclusion ?: r.status, r.conclusion == "success")
                            }
                        }
                    }
                }
                Txt("Your repositories", T.secondary, p.secondary)
                Field("Search repositories", query, { query = it })
                error?.let { Txt(it, T.secondary, p.error) }
                val list = repos
                if (list == null) Txt("Loading...", T.body, p.secondary)
                else {
                    val shown = list.filter { query.isBlank() || it.fullName.contains(query, true) }
                    if (shown.isEmpty()) Txt("No repositories found.", T.body, p.secondary)
                    shown.forEach { r ->
                        Column(Modifier.fillMaxWidth().card().tap({ selected = r }).padding(14.dp)) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Txt(r.fullName, T.body, modifier = Modifier.weight(1f), maxLines = 1)
                                if (r.isPrivate) Pill("Private", false)
                            }
                            r.description?.takeIf { it.isNotBlank() }?.let { Txt(it, T.secondary, p.secondary, maxLines = 2) }
                        }
                    }
                }
                Btn("Refresh", { tick++ }, Modifier.fillMaxWidth(), solid = false)
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    selected?.let { r -> Sheet({ selected = null }) {
        Txt(r.fullName, T.section, modifier = Modifier.padding(20.dp), maxLines = 1)
        SheetItem("Clone ${r.name}") {
            selected = null
            val base = GitRepo.repoNameFromUrl(r.cloneUrl)
            var n = base; var i = 2
            while (repo.validateName(n) != null && i < 50) { n = "$base-$i"; i++ }
            scope.launch {
                toast("Cloning...")
                val res = withContext(Dispatchers.IO) {
                    try { val d = java.io.File(repo.root, n); GitRepo.clone(r.cloneUrl, d, session.auth()); Result.success(repo.adopt(d)) } catch (e: Exception) { Result.failure(e) }
                }
                res.onSuccess { toast("Cloned ${r.name}"); onCloned(it) }.onFailure { toast(errText(it as Exception)) }
            }
        }
        SheetItem("Open in browser") { selected = null; open(ctx, r.htmlUrl) }
    } }

    if (publish && project != null) {
        var n by remember { mutableStateOf(project.name.replace(' ', '-')) }; var d by remember { mutableStateOf("") }; var priv by remember { mutableStateOf(true) }
        Sheet({ publish = false }) {
            Txt("Publish to GitHub", T.section, modifier = Modifier.padding(20.dp))
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Repository name", n, { n = it }, mono = true); Field("Description", d, { d = it })
                Segmented(listOf("Private", "Public"), if (priv) "Private" else "Public", { priv = it == "Private" })
                Txt("Create the repository and push this project", T.secondary, p.secondary)
            }
            Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("Cancel", { publish = false }, Modifier.weight(1f), solid = false)
                Btn("Create repository", {
                    publish = false; toast("Pushing...")
                    scope.launch {
                        val msg = withContext(Dispatchers.IO) {
                            try {
                                if (!GitRepo.isRepo(project.dir)) { GitIgnore.ensure(project.dir); GitRepo.init(project.dir) }
                                val g = GitRepo.open(project.dir)
                                if (g.status().let { !it.clean }) {
                                    val (un, ue) = g.getUser() ?: ((session.login ?: "AppBuilder") to "${session.login ?: "appbuilder"}@users.noreply.github.com")
                                    g.stageAll(); g.commit("Initial commit", un, ue)
                                }
                                val created = session.api()!!.createRepo(n.trim(), d, priv)
                                g.setRemote("origin", created.cloneUrl)
                                g.push(session.auth())
                                "Repository created"
                            } catch (e: Exception) { errText(e) }
                        }
                        toast(msg); tick++
                    }
                }, Modifier.weight(1f), enabled = n.isNotBlank())
            }
        }
    }

    if (pr && project != null && remote != null) {
        val g = remember { runCatching { GitRepo.open(project.dir) }.getOrNull() }
        val head = remember { g?.currentBranch() ?: "" }
        var title by remember { mutableStateOf(g?.log(1)?.firstOrNull()?.summary ?: "") }; var body by remember { mutableStateOf("") }
        val bases by produceState<List<GhBranch>>(emptyList()) { value = withContext(Dispatchers.IO) { runCatching { session.api()!!.branches("${remote.first}/${remote.second}") }.getOrDefault(emptyList()) } }
        var base by remember(bases) { mutableStateOf(bases.firstOrNull { it.name == "main" }?.name ?: bases.firstOrNull { it.name != head }?.name ?: "main") }
        Sheet({ pr = false }) {
            Txt("Open pull request", T.section, modifier = Modifier.padding(20.dp))
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Title", title, { title = it }); Field("Description", body, { body = it })
                Field("Base branch", base, { base = it }, mono = true)
            }
            Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("Cancel", { pr = false }, Modifier.weight(1f), solid = false)
                Btn("Create", {
                    pr = false
                    scope.launch {
                        val res = withContext(Dispatchers.IO) { try { Result.success(session.api()!!.createPull("${remote.first}/${remote.second}", title, body, head, base)) } catch (e: Exception) { Result.failure(e) } }
                        res.onSuccess { toast("Pull request created"); open(ctx, it.htmlUrl) }.onFailure { toast(errText(it as Exception)) }
                    }
                }, Modifier.weight(1f), enabled = title.isNotBlank() && head.isNotBlank())
            }
        }
    }
}

/** GitHub timestamps look like 2026-10-22T07:01:02Z. */
private fun parseIso(s: String): Long = runCatching {
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(s)!!.time
}.getOrDefault(0L)
