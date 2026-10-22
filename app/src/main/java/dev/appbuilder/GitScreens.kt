package dev.appbuilder

import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun kindMark(k: ChangeKind) = when (k) {
    ChangeKind.ADDED -> "A"; ChangeKind.MODIFIED -> "M"; ChangeKind.DELETED -> "D"
    ChangeKind.UNTRACKED -> "?"; ChangeKind.RENAMED -> "R"; ChangeKind.CONFLICT -> "!"
}

@Composable
private fun DiffText(diff: String) {
    val p = LocalPalette.current
    if (diff.isBlank()) { Txt("No changes", T.body, p.secondary); return }
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        diff.lines().take(2500).forEach { l ->
            val c = when {
                l.startsWith("+") && !l.startsWith("+++") -> p.okFg
                l.startsWith("-") && !l.startsWith("---") -> p.badFg
                l.startsWith("@@") -> p.secondary
                else -> p.ink
            }
            androidx.compose.foundation.text.BasicText(l.ifEmpty { " " }, style = T.code.copy(fontSize = 11.sp, color = c), softWrap = false)
        }
    }
}

/** Git for one project: changes, history, branches, remotes. Every repo call runs off the main thread. */
@Composable
fun GitScreen(project: Project, onBack: () -> Unit, onOpenGitHub: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val session = remember { GitHubSession(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf("Changes") }
    var message by remember { mutableStateOf("") }
    var amend by remember { mutableStateOf(false) }
    var diff by remember { mutableStateOf<Pair<String, String>?>(null) }
    var discard by remember { mutableStateOf<String?>(null) }
    var branchSheet by remember { mutableStateOf<GitBranch?>(null) }
    var newBranch by remember { mutableStateOf(false) }
    var addRemote by remember { mutableStateOf(false) }
    var ignoreEdit by remember { mutableStateOf<String?>(null) }
    var commitView by remember { mutableStateOf<GitCommit?>(null) }
    val isRepo = remember(tick) { GitRepo.isRepo(project.dir) }

    class Snap(val status: GitStatus, val log: List<GitCommit>, val branches: List<GitBranch>, val remotes: List<GitRemote>, val user: Pair<String, String>?)
    val snap by produceState<Snap?>(null, tick, isRepo) {
        value = if (!isRepo) null else withContext(Dispatchers.IO) {
            runCatching { GitRepo.open(project.dir).let { Snap(it.status(), runCatching { it.log(60) }.getOrDefault(emptyList()), it.branches(), it.remotes(), it.getUser()) } }.getOrNull()
        }
    }
    var name by remember(snap) { mutableStateOf(snap?.user?.first ?: "") }
    var email by remember(snap) { mutableStateOf(snap?.user?.second ?: "") }

    fun work(label: String, block: () -> String?) {
        if (busy != null) return
        busy = label
        scope.launch {
            val res = withContext(Dispatchers.IO) { try { block() } catch (e: GitException) { e.msg } catch (e: Exception) { GitMsg.generic(e.message ?: e.toString()) } }
            busy = null; tick++
            if (!res.isNullOrBlank()) Toast.makeText(ctx, tr(res), Toast.LENGTH_LONG).show()
        }
    }
    fun repo() = GitRepo.open(project.dir)
    val progress: (String, Int) -> Unit = { m, _ -> busy = m }

    Column(Modifier.fillMaxSize()) {
        TopBar("Git", project.name, onBack = onBack, actions = { IconBtn(I.more, "More", { ignoreEdit = GitIgnore.read(project.dir) }) })
        if (!isRepo) {
            Column(Modifier.weight(1f).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt("Not a Git repository", T.title.copy(fontSize = 26.sp))
                Txt("This project is not a Git repository yet.", T.body, p.secondary)
                Btn("Init repository", {
                    work("Init repository") { GitIgnore.ensure(project.dir); GitRepo.init(project.dir); null }
                }, Modifier.fillMaxWidth(), accent = true, enabled = busy == null)
            }
        } else {
            val s = snap
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (s != null) {
                    Txt(if (s.status.detached) "Detached HEAD" else "On branch ${s.status.branch ?: "?"}", T.section)
                    if (s.status.upstream != null) Txt("${s.status.upstream} · ↑${s.status.ahead} ↓${s.status.behind}", T.code, p.secondary)
                }
                busy?.let { Txt(it, T.secondary, p.secondary) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Fetch", { work("Fetching...") { repo().fetch(session.auth(), progress) } }, Modifier.weight(1f), solid = false, enabled = busy == null)
                    Btn("Pull", { work("Pulling...") { repo().pull(session.auth(), progress) } }, Modifier.weight(1f), solid = false, enabled = busy == null)
                    Btn("Push", {
                        if (session.auth() == null && s?.remotes?.any { it.url.startsWith("https://") } == true) Toast.makeText(ctx, tr("Sign in to GitHub in Settings to push."), Toast.LENGTH_LONG).show()
                        else work("Pushing...") { repo().push(session.auth(), onProgress = progress) }
                    }, Modifier.weight(1f), solid = false, enabled = busy == null)
                }
                if (s?.remotes?.isEmpty() == true && session.signedIn) Btn("Publish to GitHub", onOpenGitHub, Modifier.fillMaxWidth(), solid = false)
                if (s?.status?.conflicts?.isNotEmpty() == true || (s != null && runCatching { repo().isMerging() }.getOrDefault(false)))
                    Btn("Abort merge", { work("Abort merge") { repo().abortMerge(); null } }, Modifier.fillMaxWidth(), solid = false)
                Segmented(listOf("Changes", "History", "Branches", "Remotes"), tab, { tab = it })
                when (tab) {
                    "Changes" -> if (s != null) {
                        val st = s.status
                        if (st.clean) Txt("Working tree clean", T.body, p.secondary)
                        if (st.conflicts.isNotEmpty()) { Txt("Conflicts", T.secondary, p.secondary); st.conflicts.forEach { f -> Txt(f, T.code, p.error, Modifier.tap({ diff = f to "" })) } }
                        if (st.staged.isNotEmpty()) {
                            Txt("Staged", T.secondary, p.secondary)
                            st.staged.forEach { c -> ChangeRow(kindMark(c.kind), c.path, "Unstage", { work("Unstage") { repo().unstage(listOf(c.path)); null } }, {
                                scope.launch { diff = c.path to withContext(Dispatchers.IO) { runCatching { repo().diff(c.path, true) }.getOrDefault("") } }
                            }) }
                        }
                        if (st.unstaged.isNotEmpty()) {
                            Txt("Unstaged", T.secondary, p.secondary)
                            st.unstaged.forEach { c -> ChangeRow(kindMark(c.kind), c.path, "Stage", { work("Stage") { repo().stage(listOf(c.path)); null } }, {
                                scope.launch { diff = c.path to withContext(Dispatchers.IO) { runCatching { repo().diff(c.path, false) }.getOrDefault("") } }
                            }, onLong = { discard = c.path }) }
                        }
                        if (st.untracked.isNotEmpty()) {
                            Txt("Untracked", T.secondary, p.secondary)
                            st.untracked.forEach { f -> ChangeRow("?", f, "Stage", { work("Stage") { repo().stage(listOf(f)); null } }, {}) }
                        }
                        if (!st.clean) Btn("Stage all", { work("Stage") { repo().stageAll(); null } }, Modifier.fillMaxWidth(), solid = false)
                        Divider()
                        if (s.user == null) {
                            Txt("Git identity", T.secondary, p.secondary)
                            Field("Author name", name, { name = it }); Field("Author email", email, { email = it })
                        }
                        Field("Commit message", message, { message = it })
                        Btn(if (amend) "Amend last commit" else "Commit", {
                            val n = if (s.user != null) s.user.first else name; val e = if (s.user != null) s.user.second else email
                            work("Commit") { val r = repo(); if (s.user == null && n.isNotBlank() && e.isNotBlank()) r.setUser(n, e); val c = r.commit(message, n, e, amend); message = ""; amend = false; "Committed ${c.shortId}" }
                        }, Modifier.fillMaxWidth(), accent = true, enabled = busy == null && message.isNotBlank())
                        Btn(if (amend) "Commit" else "Amend last commit", { amend = !amend }, Modifier.fillMaxWidth(), solid = false)
                    }
                    "History" -> s?.log?.let { log ->
                        if (log.isEmpty()) Txt("No commits yet", T.body, p.secondary)
                        log.forEach { c ->
                            Column(Modifier.fillMaxWidth().card().tap({ commitView = c }).padding(14.dp)) {
                                Txt(c.summary, T.body, maxLines = 2)
                                Txt("${c.shortId} · ${c.author} · ${timeAgo(c.time)}", T.code.copy(fontSize = 11.sp), p.secondary)
                            }
                        }
                    }
                    "Branches" -> s?.branches?.let { bs ->
                        Btn("New branch", { newBranch = true }, Modifier.fillMaxWidth(), solid = false, icon = I.plus)
                        Txt("Local branches", T.secondary, p.secondary)
                        bs.filter { !it.remote }.forEach { b -> BranchRow(b) { branchSheet = b } }
                        Txt("Remote branches", T.secondary, p.secondary)
                        bs.filter { it.remote }.forEach { b -> BranchRow(b) { branchSheet = b } }
                    }
                    else -> s?.remotes?.let { rs ->
                        rs.forEach { r -> Column(Modifier.fillMaxWidth().card().padding(14.dp)) { Txt(r.name, T.section); Txt(r.url.replace(Regex("//[^/@]+@"), "//"), T.code.copy(fontSize = 11.sp), p.secondary) } }
                        Btn("Add remote", { addRemote = true }, Modifier.fillMaxWidth(), solid = false, icon = I.plus)
                        Btn("Edit .gitignore", { ignoreEdit = GitIgnore.read(project.dir) }, Modifier.fillMaxWidth(), solid = false)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    diff?.let { (f, text) -> Sheet({ diff = null }) {
        Txt("Diff", T.secondary, p.secondary, Modifier.padding(start = 20.dp, top = 12.dp))
        Txt(f, T.section, modifier = Modifier.padding(horizontal = 20.dp), maxLines = 1)
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(20.dp)) { DiffText(text) }
    } }
    commitView?.let { c ->
        val text by produceState("", c) { value = withContext(Dispatchers.IO) { runCatching { GitRepo.open(project.dir).diffCommit(c.id) }.getOrDefault("") } }
        Sheet({ commitView = null }) {
            Txt(c.summary, T.section, modifier = Modifier.padding(20.dp), maxLines = 2)
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) { DiffText(text) }
        }
    }
    discard?.let { f -> Sheet({ discard = null }) {
        Txt("Discard changes to $f?", T.section, modifier = Modifier.padding(20.dp))
        Txt("This cannot be undone.", T.body, p.secondary, Modifier.padding(horizontal = 20.dp))
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Cancel", { discard = null }, Modifier.weight(1f), solid = false)
            Btn("Discard", { discard = null; work("Discard") { repo().discard(listOf(f)); null } }, Modifier.weight(1f))
        }
    } }
    branchSheet?.let { b -> Sheet({ branchSheet = null }) {
        Txt(b.name, T.section, modifier = Modifier.padding(20.dp))
        if (!b.current && !b.remote) {
            SheetItem("Switch branch") { branchSheet = null; work("Switch") { repo().checkout(b.name); null } }
            SheetItem("Merge") { branchSheet = null; work("Merge") { repo().mergeBranch(b.name) } }
            SheetItem("Delete branch", danger = true) { branchSheet = null; work("Delete") { try { repo().deleteBranch(b.name); null } catch (e: GitException) { if (e.msg.contains("not fully merged")) { repo().deleteBranch(b.name, true); null } else throw e } } }
        } else if (b.remote) SheetItem("Switch branch") { branchSheet = null; work("Switch") { repo().checkout(b.name.substringAfter('/')); null } }
        else Txt("Current", T.body, p.secondary, Modifier.padding(20.dp))
    } }
    if (newBranch) { var n by remember { mutableStateOf("") }; Sheet({ newBranch = false }) {
        Txt("New branch", T.section, modifier = Modifier.padding(20.dp))
        Box(Modifier.padding(horizontal = 20.dp)) { Field("Branch name", n, { n = it }, mono = true) }
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Cancel", { newBranch = false }, Modifier.weight(1f), solid = false)
            Btn("Create", { newBranch = false; work("Create") { repo().createBranch(n.trim(), true); null } }, Modifier.weight(1f), enabled = n.isNotBlank())
        }
    } }
    if (addRemote) { var n by remember { mutableStateOf("origin") }; var u by remember { mutableStateOf("") }; Sheet({ addRemote = false }) {
        Txt("Add remote", T.section, modifier = Modifier.padding(20.dp))
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Field("Branch name", n, { n = it }, mono = true); Field("Remote URL", u, { u = it }, mono = true) }
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Cancel", { addRemote = false }, Modifier.weight(1f), solid = false)
            Btn("Add", { addRemote = false; work("Add") { repo().setRemote(n.trim(), u.trim()); "Remote added" } }, Modifier.weight(1f), enabled = u.isNotBlank())
        }
    } }
    ignoreEdit?.let { initial -> var t by remember(initial) { mutableStateOf(initial) }; Sheet({ ignoreEdit = null }) {
        Txt(".gitignore", T.section, modifier = Modifier.padding(20.dp))
        Box(Modifier.padding(horizontal = 20.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            androidx.compose.foundation.text.BasicTextField(t, { t = it }, textStyle = T.code.copy(fontSize = 13.sp, color = p.ink), cursorBrush = androidx.compose.ui.graphics.SolidColor(p.accent), modifier = Modifier.fillMaxWidth().card().padding(12.dp))
        }
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Cancel", { ignoreEdit = null }, Modifier.weight(1f), solid = false)
            Btn("Save", { GitIgnore.write(project.dir, t); ignoreEdit = null; tick++ }, Modifier.weight(1f))
        }
    } }
}

@Composable
private fun ChangeRow(mark: String, path: String, action: String, onAction: () -> Unit, onOpen: () -> Unit, onLong: (() -> Unit)? = null) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().card().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(mark, T.code, p.secondary, Modifier.width(22.dp))
        Txt(path, T.code.copy(fontSize = 12.sp), modifier = Modifier.weight(1f).tap(onOpen).padding(vertical = 14.dp), maxLines = 2)
        Box(Modifier.heightIn(min = 48.dp).tap(onAction).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { Txt(action, T.button.copy(fontSize = 13.sp)) }
        if (onLong != null) Box(Modifier.heightIn(min = 48.dp).tap(onLong).padding(end = 12.dp), contentAlignment = Alignment.Center) { Txt("Discard", T.button.copy(fontSize = 13.sp), p.error) }
    }
}

@Composable
private fun BranchRow(b: GitBranch, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().card().tap(onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(b.name, T.code, modifier = Modifier.weight(1f), maxLines = 1)
        if (b.current) Pill("Current", true) else b.headShort?.let { Txt(it, T.code.copy(fontSize = 11.sp), p.secondary) }
    }
}
