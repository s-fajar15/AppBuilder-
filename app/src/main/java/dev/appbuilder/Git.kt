package dev.appbuilder

import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.api.errors.InvalidConfigurationException
import org.eclipse.jgit.api.errors.NoHeadException
import org.eclipse.jgit.api.errors.NotMergedException
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.ignore.IgnoreNode
import org.eclipse.jgit.lib.BatchingProgressMonitor
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.ByteArrayOutputStream
import java.io.File

/** Every user-facing English message the engine can produce. Translations live in [GitTexts]. */
object GitMsg {
    const val NOT_A_REPO = "Not a Git repository"
    const val NOTHING_TO_COMMIT = "Nothing to commit"
    const val EMPTY_MESSAGE = "Enter a commit message"
    const val NO_AUTHOR = "Set your name and email to commit"
    const val NOTHING_TO_AMEND = "There is no commit to amend"
    const val CONFLICTS_REMAIN = "Resolve the conflicts before committing"
    const val AUTH_FAILED = "Authentication failed. Check your GitHub token and its repo permission."
    const val REPO_NOT_FOUND = "Repository not found. Check the URL and your access."
    const val NETWORK = "Could not reach the server. Check your internet connection."
    const val SSH_UNSUPPORTED = "SSH addresses are not supported. Use an https:// URL."
    const val INVALID_URL = "Enter a valid repository URL"
    const val TARGET_NOT_EMPTY = "The target folder is not empty"
    const val ALREADY_REPO = "This folder is already a Git repository"
    const val NO_REMOTE = "No remote is set. Add a remote first."
    const val NO_UPSTREAM = "This branch has no upstream branch. Push it first."
    const val DETACHED = "You are not on a branch. Switch to a branch first."
    const val NO_COMMITS = "There are no commits yet"
    const val UP_TO_DATE = "Already up to date."
    const val EVERYTHING_UP_TO_DATE = "Everything up to date."
    const val PUSH_REJECTED = "Push rejected: the remote has new commits. Pull first."
    const val PUSH_REJECTED_PROTECTED = "Push rejected by the remote. The branch may be protected."
    const val CANNOT_DELETE_CURRENT = "Cannot delete the branch you are on"
    const val INVALID_BRANCH = "Invalid branch name"
    const val NO_SUCH_BRANCH = "Branch not found"
    const val INVALID_REMOTE_NAME = "Invalid remote name"
    const val NO_SUCH_COMMIT = "Commit not found"
    const val NO_SUCH_REMOTE = "Remote not found"
    const val BUSY = "Another Git operation is in progress. Try again."

    fun branchExists(x: String) = "Branch '$x' already exists"
    fun branchNotMerged(x: String) = "Branch '$x' is not fully merged. Delete anyway?"
    fun checkoutBlocked(files: List<String>) = "Cannot switch: your changes to these files would be overwritten: ${files.joinToString(", ")}"
    fun pullBlocked(files: List<String>) = "Cannot merge: local changes to these files would be overwritten: ${files.joinToString(", ")}"
    fun mergeConflicts(files: List<String>) = "Merge conflicts in: ${files.joinToString(", ")}"
    fun fastForward(n: Int) = "Fast-forward: $n file(s) changed"
    fun merged(n: Int) = "Merged: $n file(s) changed"
    fun mergedBranch(x: String) = "Merged '$x'"
    fun pushed(remote: String, branch: String) = "Pushed $branch to $remote"
    fun pushFailed(reason: String) = "Push failed: $reason"
    fun fetched(n: Int) = "Fetched $n update(s)"
    fun generic(m: String) = "Git error: $m"
}

class GitException(val msg: String, cause: Throwable? = null) : Exception(msg, cause)

data class GitAuth(val user: String, val token: String)

enum class ChangeKind { ADDED, MODIFIED, DELETED, UNTRACKED, RENAMED, CONFLICT }

data class GitFileChange(val path: String, val kind: ChangeKind)

data class GitStatus(
    val branch: String?,
    val detached: Boolean,
    val headShort: String?,
    val upstream: String?,
    val ahead: Int,
    val behind: Int,
    val staged: List<GitFileChange>,
    val unstaged: List<GitFileChange>,
    val untracked: List<String>,
    val conflicts: List<String>,
) {
    val clean: Boolean get() = staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty() && conflicts.isEmpty()
}

data class GitCommit(
    val id: String, val shortId: String, val summary: String, val message: String,
    val author: String, val email: String, val time: Long,
)

data class GitBranch(val name: String, val current: Boolean, val remote: Boolean, val upstream: String?, val headShort: String?)

data class GitRemote(val name: String, val url: String)

/** Android needs a writable home for JGit's user config lookups. Call once, before any other Git call. */
object GitEnv {
    fun configure(homeDir: File) {
        val h = System.getProperty("user.home")
        if (h.isNullOrBlank() || h == "/" || !File(h).canWrite()) {
            homeDir.mkdirs()
            System.setProperty("user.home", homeDir.path)
        }
    }
}

// ---------------------------------------------------------------- helpers

private class Monitor(private val cb: (String, Int) -> Unit) : BatchingProgressMonitor() {
    override fun onUpdate(taskName: String?, workCurr: Int) { cb(taskName ?: "", -1) }
    override fun onEndTask(taskName: String?, workCurr: Int) { cb(taskName ?: "", -1) }
    override fun onUpdate(taskName: String?, workCurr: Int, workTotal: Int, percentDone: Int) { cb(taskName ?: "", percentDone) }
    override fun onEndTask(taskName: String?, workCurr: Int, workTotal: Int, percentDone: Int) { cb(taskName ?: "", 100) }
}

private fun monitor(cb: (String, Int) -> Unit): ProgressMonitor = Monitor(cb)

private fun creds(a: GitAuth?) = a?.let { UsernamePasswordCredentialsProvider(it.user, it.token) }

private fun friendly(e: Throwable): GitException {
    if (e is GitException) return e
    if (e is RepositoryNotFoundException) return GitException(GitMsg.NOT_A_REPO, e)
    if (e is CheckoutConflictException) return GitException(GitMsg.checkoutBlocked(e.conflictingPaths.orEmpty()), e)
    if (e is NotMergedException) return GitException(GitMsg.generic(e.message ?: "not merged"), e)
    if (e is InvalidConfigurationException) return GitException(GitMsg.NO_UPSTREAM, e)
    if (e is NoHeadException) return GitException(GitMsg.NO_COMMITS, e)
    val sb = StringBuilder()
    var t: Throwable? = e
    var depth = 0
    var notFound = false
    while (t != null && depth++ < 8) {
        if (t is NoRemoteRepositoryException) notFound = true
        sb.append(t.javaClass.simpleName).append(' ').append(t.message ?: "").append(' ')
        t = if (t.cause === t) null else t.cause
    }
    val s = sb.toString().lowercase()
    val msg = when {
        "not authorized" in s || "authentication" in s || "401" in s || "403" in s ||
            "invalid credentials" in s || ("permission" in s && "denied" in s) -> GitMsg.AUTH_FAILED
        notFound || "repository not found" in s || "404" in s -> GitMsg.REPO_NOT_FOUND
        "unknownhost" in s || "unable to connect" in s || "failed to connect" in s || "timed out" in s ||
            "connection" in s || "network" in s || "ssl" in s && "handshake" in s -> GitMsg.NETWORK
        else -> GitMsg.generic(e.message ?: e.javaClass.simpleName)
    }
    return GitException(msg, e)
}

private inline fun <T> guard(block: () -> T): T =
    try { block() } catch (e: GitException) { throw e } catch (e: Exception) { throw friendly(e) }

internal fun normalizeGitUrl(raw: String): String {
    var u = raw.trim()
    if (u.isEmpty()) throw GitException(GitMsg.INVALID_URL)
    val low = u.lowercase()
    if (low.startsWith("ssh://") || low.startsWith("git@") || Regex("^[\\w.-]+@[\\w.-]+:.+").matches(u)) throw GitException(GitMsg.SSH_UNSUPPORTED)
    u = when {
        low.startsWith("http://") || low.startsWith("https://") || low.startsWith("file:") -> u
        low.startsWith("github.com/") || low.startsWith("www.github.com/") -> "https://$u"
        u.startsWith("/") || File(u).isDirectory -> u
        else -> throw GitException(GitMsg.INVALID_URL)
    }
    while (u.length > 1 && u.endsWith("/")) u = u.dropLast(1)
    if (u.lowercase().startsWith("http") && !Regex("^https?://[^/\\s]+/.+").matches(u)) throw GitException(GitMsg.INVALID_URL)
    return u
}

private fun shortHead(id: ObjectId?) = id?.name?.take(7)

private fun toCommit(c: RevCommit) = GitCommit(
    id = c.name, shortId = c.name.take(7), summary = c.shortMessage.orEmpty(), message = c.fullMessage.orEmpty().trim(),
    author = c.authorIdent.name.orEmpty(), email = c.authorIdent.emailAddress.orEmpty(), time = c.commitTime * 1000L,
)

private fun format(repo: Repository, a: AbstractTreeIterator, b: AbstractTreeIterator, path: String?, renames: Boolean, keep: (DiffEntry) -> Boolean = { true }): String {
    val out = ByteArrayOutputStream()
    DiffFormatter(out).use { f ->
        f.setRepository(repo)
        f.isDetectRenames = renames
        if (!path.isNullOrEmpty()) f.pathFilter = PathFilter.create(path.trim('/'))
        for (e in f.scan(a, b)) if (keep(e)) f.format(e)
        f.flush()
    }
    return out.toString("UTF-8")
}

private fun allAdded(path: String, bytes: ByteArray): String {
    val head = "diff --git a/$path b/$path\nnew file mode 100644\n"
    if (bytes.any { it == 0.toByte() }) return head + "Binary files /dev/null and b/$path differ\n"
    val text = String(bytes, Charsets.UTF_8)
    if (text.isEmpty()) return head
    val lines = text.split("\n").let { if (text.endsWith("\n")) it.dropLast(1) else it }
    val sb = StringBuilder(head).append("--- /dev/null\n+++ b/").append(path).append("\n@@ -0,0 +1,").append(lines.size).append(" @@\n")
    lines.forEach { sb.append('+').append(it).append('\n') }
    if (!text.endsWith("\n")) sb.append("\\ No newline at end of file\n")
    return sb.toString()
}

private fun countChanged(repo: Repository, oldId: ObjectId?, newId: ObjectId): Int =
    repo.newObjectReader().use { reader ->
        RevWalk(repo).use { rw ->
            fun tree(id: ObjectId?): AbstractTreeIterator =
                if (id == null) EmptyTreeIterator() else CanonicalTreeParser().also { it.reset(reader, rw.parseCommit(id).tree.id) }
            DiffFormatter(ByteArrayOutputStream()).use { f -> f.setRepository(repo); f.scan(tree(oldId), tree(newId)).size }
        }
    }

// ---------------------------------------------------------------- repository

class GitRepo private constructor(val dir: File) {

    companion object {
        fun isRepo(dir: File): Boolean = File(dir, ".git").exists()

        fun init(dir: File, initialBranch: String = "main"): GitRepo = guard {
            if (isRepo(dir)) throw GitException(GitMsg.ALREADY_REPO)
            dir.mkdirs()
            Git.init().setDirectory(dir).setInitialBranch(initialBranch).call().use { g ->
                // Android storage does not track the executable bit reliably; avoid phantom mode changes.
                g.repository.config.apply { setBoolean("core", null, "filemode", false); save() }
            }
            GitRepo(dir)
        }

        fun open(dir: File): GitRepo {
            if (!isRepo(dir)) throw GitException(GitMsg.NOT_A_REPO)
            return GitRepo(dir)
        }

        fun clone(
            url: String, dir: File, auth: GitAuth? = null, branch: String? = null,
            onProgress: (String, Int) -> Unit = { _, _ -> },
        ): GitRepo {
            val u = normalizeGitUrl(url)
            if (dir.exists() && (!dir.isDirectory || dir.list().orEmpty().isNotEmpty())) throw GitException(GitMsg.TARGET_NOT_EMPTY)
            val existed = dir.exists()
            try {
                guard {
                    val cmd = Git.cloneRepository().setURI(u).setDirectory(dir).setProgressMonitor(monitor(onProgress))
                    creds(auth)?.let { cmd.setCredentialsProvider(it) }
                    if (!branch.isNullOrBlank()) cmd.setBranch(branch)
                    cmd.call().use { g -> g.repository.config.apply { setBoolean("core", null, "filemode", false); save() } }
                }
            } catch (e: Exception) {
                if (existed) dir.listFiles()?.forEach { it.deleteRecursively() } else dir.deleteRecursively()
                throw e
            }
            return GitRepo(dir)
        }

        /** A project-name-safe folder name for a repository URL (letters, digits, space, - and _; starts with a letter; max 40). */
        fun repoNameFromUrl(url: String): String {
            var s = url.trim().trimEnd('/')
            if (s.endsWith(".git", true)) s = s.dropLast(4)
            s = s.trimEnd('/')
            val last = s.substringAfterLast('/').substringAfterLast(':')
            var n = last.replace(Regex("[^A-Za-z0-9 _-]"), "-").trim('-', ' ')
            if (n.isEmpty()) n = "repo"
            if (!n[0].isLetter() || n[0].code > 127) n = "repo-$n"
            return n.take(40)
        }
    }

    private fun <T> withGit(block: (Git) -> T): T = guard { Git.open(dir).use(block) }

    private fun defaultRemote(g: Git): String? {
        val names = g.repository.config.getSubsections("remote")
        return if ("origin" in names) "origin" else names.firstOrNull()
    }

    // ---- status

    fun currentBranch(): String? = withGit { g ->
        if (g.repository.fullBranch?.startsWith(Constants.R_HEADS) == true) g.repository.branch else null
    }

    fun isMerging(): Boolean = withGit { it.repository.repositoryState.name.startsWith("MERGING") }

    fun status(): GitStatus = withGit { g ->
        val repo = g.repository
        val st = g.status().call()
        val full = repo.fullBranch.orEmpty()
        val onBranch = full.startsWith(Constants.R_HEADS)
        val branch = if (onBranch) repo.branch else null
        val head = repo.resolve(Constants.HEAD)
        var upstream: String? = null; var ahead = 0; var behind = 0
        if (branch != null) {
            BranchTrackingStatus.of(repo, branch)?.let {
                upstream = Repository.shortenRefName(it.remoteTrackingBranch); ahead = it.aheadCount; behind = it.behindCount
            } ?: BranchConfig(repo.config, branch).trackingBranch?.let { upstream = Repository.shortenRefName(it) }
        }
        val conflicts = st.conflicting.sorted()
        GitStatus(
            branch = branch, detached = !onBranch && head != null, headShort = shortHead(head), upstream = upstream,
            ahead = ahead, behind = behind,
            staged = (st.added.map { GitFileChange(it, ChangeKind.ADDED) } + st.changed.map { GitFileChange(it, ChangeKind.MODIFIED) } +
                st.removed.map { GitFileChange(it, ChangeKind.DELETED) }).sortedBy { it.path },
            unstaged = (st.modified.map { GitFileChange(it, ChangeKind.MODIFIED) } + st.missing.map { GitFileChange(it, ChangeKind.DELETED) })
                .filter { it.path !in conflicts }.sortedBy { it.path },
            untracked = st.untracked.sorted(),
            conflicts = conflicts,
        )
    }

    fun hasUncommittedChanges(): Boolean {
        val s = status()
        return s.staged.isNotEmpty() || s.unstaged.isNotEmpty() || s.conflicts.isNotEmpty()
    }

    // ---- staging

    fun stage(paths: List<String>) = withGit { g ->
        for (p in paths) {
            if (p == "." || File(dir, p).exists()) g.add().addFilepattern(p).call()
            else g.rm().setCached(true).addFilepattern(p).call()
        }
    }

    /** Stages everything not ignored, including deletions. Makes sure AppBuilder's own files stay out first. */
    fun stageAll() {
        GitIgnore.ensure(dir)
        withGit { g ->
            g.add().addFilepattern(".").call()
            g.add().setUpdate(true).addFilepattern(".").call()
        }
    }

    fun unstage(paths: List<String>) = withGit { g ->
        val hasHead = g.repository.resolve(Constants.HEAD) != null
        for (p in paths) {
            if (hasHead) g.reset().addPath(p).call() else g.rm().setCached(true).addFilepattern(p).call()
        }
    }

    /** Irreversible: restores tracked files from the index and deletes untracked ones. */
    fun discard(paths: List<String>) {
        val st = status()
        withGit { g ->
            for (p in paths) {
                val doomed = st.untracked.filter { it == p || it.startsWith("$p/") }
                if (doomed.isNotEmpty()) {
                    doomed.forEach { File(dir, it).delete() }
                    File(dir, p).takeIf { it.isDirectory }?.delete()
                } else {
                    g.checkout().addPath(p).call()
                }
            }
        }
    }

    // ---- commit

    fun commit(message: String, name: String, email: String, amend: Boolean = false): GitCommit {
        if (name.isBlank() || email.isBlank()) throw GitException(GitMsg.NO_AUTHOR)
        val st = status()
        val hasHead = withGit { it.repository.resolve(Constants.HEAD) != null }
        if (amend && !hasHead) throw GitException(GitMsg.NOTHING_TO_AMEND)
        if (message.isBlank() && !amend) throw GitException(GitMsg.EMPTY_MESSAGE)
        if (st.conflicts.isNotEmpty()) throw GitException(GitMsg.CONFLICTS_REMAIN)
        return withGit { g ->
            val resolved = g.repository.repositoryState == RepositoryState.MERGING_RESOLVED
            if (!amend && st.staged.isEmpty() && !resolved) throw GitException(GitMsg.NOTHING_TO_COMMIT)
            val who = PersonIdent(name.trim(), email.trim())
            val cmd = g.commit().setAuthor(who).setCommitter(who).setAmend(amend).setAllowEmpty(amend || resolved)
            if (message.isNotBlank()) cmd.setMessage(message.trim())
            toCommit(cmd.call())
        }
    }

    fun log(limit: Int = 50, path: String? = null): List<GitCommit> = withGit { g ->
        if (g.repository.resolve(Constants.HEAD) == null) return@withGit emptyList()
        val cmd = g.log().setMaxCount(limit)
        if (!path.isNullOrEmpty()) cmd.addPath(path)
        cmd.call().map(::toCommit)
    }

    fun readFileAtHead(path: String): String? = withGit { g ->
        val id = try { g.repository.resolve("HEAD:$path") } catch (_: Exception) { null } ?: return@withGit null
        try { String(g.repository.open(id).bytes, Charsets.UTF_8) } catch (_: Exception) { null }
    }

    // ---- branches

    fun branches(): List<GitBranch> = withGit { g ->
        val repo = g.repository
        val cur = if (repo.fullBranch?.startsWith(Constants.R_HEADS) == true) repo.branch else null
        val local = repo.refDatabase.getRefsByPrefix(Constants.R_HEADS).map { r ->
            val n = Repository.shortenRefName(r.name)
            GitBranch(n, n == cur, false, BranchConfig(repo.config, n).trackingBranch?.let { Repository.shortenRefName(it) }, shortHead(r.objectId))
        }.sortedBy { it.name }.toMutableList()
        if (cur != null && local.none { it.name == cur }) local.add(0, GitBranch(cur, true, false, null, null)) // unborn branch
        val remote = repo.refDatabase.getRefsByPrefix(Constants.R_REMOTES)
            .filter { !it.name.endsWith("/HEAD") }
            .map { GitBranch(Repository.shortenRefName(it.name), false, true, null, shortHead(it.objectId)) }
            .sortedBy { it.name }
        local + remote
    }

    fun createBranch(name: String, checkout: Boolean = true) = withGit { g ->
        val repo = g.repository
        val n = name.trim()
        if (n.isEmpty() || !Repository.isValidRefName(Constants.R_HEADS + n)) throw GitException(GitMsg.INVALID_BRANCH)
        if (repo.exactRef(Constants.R_HEADS + n) != null) throw GitException(GitMsg.branchExists(n))
        if (repo.resolve(Constants.HEAD) == null) {
            if (!checkout) throw GitException(GitMsg.NO_COMMITS)
            repo.updateRef(Constants.HEAD).link(Constants.R_HEADS + n) // unborn: just point HEAD at the new name
        } else if (checkout) g.checkout().setCreateBranch(true).setName(n).call()
        else g.branchCreate().setName(n).call()
    }

    fun checkout(name: String) = withGit { g ->
        val repo = g.repository
        val n = name.trim()
        if (repo.exactRef(Constants.R_HEADS + n) != null) {
            g.checkout().setName(n).call()
        } else if (repo.exactRef(Constants.R_REMOTES + n) != null && n.contains('/')) {
            val local = n.substringAfter('/')
            if (repo.exactRef(Constants.R_HEADS + local) != null) g.checkout().setName(local).call()
            else g.checkout().setCreateBranch(true).setName(local).setStartPoint(Constants.R_REMOTES + n)
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK).call()
        } else throw GitException(GitMsg.NO_SUCH_BRANCH)
    }

    fun deleteBranch(name: String, force: Boolean = false) = withGit { g ->
        val repo = g.repository
        if (repo.fullBranch == Constants.R_HEADS + name) throw GitException(GitMsg.CANNOT_DELETE_CURRENT)
        if (repo.exactRef(Constants.R_HEADS + name) == null) throw GitException(GitMsg.NO_SUCH_BRANCH)
        try {
            g.branchDelete().setBranchNames(name).setForce(force).call()
        } catch (e: NotMergedException) {
            throw GitException(GitMsg.branchNotMerged(name), e)
        }
        Unit
    }

    fun mergeBranch(name: String): String = withGit { g ->
        val repo = g.repository
        val ref = repo.findRef(name) ?: throw GitException(GitMsg.NO_SUCH_BRANCH)
        val before = repo.resolve(Constants.HEAD)
        val r = g.merge().include(ref).setFastForward(MergeCommand.FastForwardMode.FF).call()
        summarize(repo, r, before, GitMsg.mergedBranch(Repository.shortenRefName(ref.name)), GitMsg::checkoutBlocked)
    }

    private fun summarize(repo: Repository, r: MergeResult?, before: ObjectId?, mergedText: String, blocked: (List<String>) -> String): String {
        if (r == null) return GitMsg.UP_TO_DATE
        val after = repo.resolve(Constants.HEAD)
        return when (r.mergeStatus) {
            MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> GitMsg.UP_TO_DATE
            MergeResult.MergeStatus.FAST_FORWARD, MergeResult.MergeStatus.FAST_FORWARD_SQUASHED ->
                GitMsg.fastForward(if (after != null) countChanged(repo, before, after) else 0)
            MergeResult.MergeStatus.MERGED, MergeResult.MergeStatus.MERGED_NOT_COMMITTED ->
                if (mergedText.isNotEmpty() && before != null && after != null && false) mergedText else GitMsg.merged(if (after != null) countChanged(repo, before, after) else 0)
            MergeResult.MergeStatus.CONFLICTING -> GitMsg.mergeConflicts(r.conflicts.orEmpty().keys.sorted())
            MergeResult.MergeStatus.FAILED -> throw GitException(blocked(r.failingPaths.orEmpty().keys.sorted()))
            else -> GitMsg.generic(r.mergeStatus.toString())
        }
    }

    fun abortMerge() = withGit { g -> g.reset().setMode(ResetCommand.ResetType.HARD).call(); Unit }

    // ---- diff

    /** Unified diff. Untracked files are included only when [path] names one. */
    fun diff(path: String? = null, staged: Boolean = false): String = withGit { g ->
        val repo = g.repository
        val p = path?.trim('/')?.takeIf { it.isNotEmpty() }
        repo.newObjectReader().use { reader ->
            if (staged) {
                val head = repo.resolve("HEAD^{tree}")
                val a: AbstractTreeIterator = if (head == null) EmptyTreeIterator() else CanonicalTreeParser().also { it.reset(reader, head) }
                format(repo, a, DirCacheIterator(repo.readDirCache()), p, true)
            } else {
                val dc = repo.readDirCache()
                val f = if (p != null) File(dir, p) else null
                if (p != null && dc.findEntry(p) < 0 && f != null && f.isFile && !GitIgnore.isIgnored(dir, p)) allAdded(p, f.readBytes())
                else format(repo, DirCacheIterator(dc), FileTreeIterator(repo), p, false) { it.changeType != DiffEntry.ChangeType.ADD }
            }
        }
    }

    fun diffCommit(id: String): String = withGit { g ->
        val repo = g.repository
        val oid = try { repo.resolve(id) } catch (_: Exception) { null } ?: throw GitException(GitMsg.NO_SUCH_COMMIT)
        repo.newObjectReader().use { reader ->
            RevWalk(repo).use { rw ->
                val c = try { rw.parseCommit(oid) } catch (_: Exception) { throw GitException(GitMsg.NO_SUCH_COMMIT) }
                val b = CanonicalTreeParser().also { it.reset(reader, c.tree.id) }
                val a: AbstractTreeIterator = if (c.parentCount == 0) EmptyTreeIterator()
                else CanonicalTreeParser().also { it.reset(reader, rw.parseCommit(c.getParent(0).id).tree.id) }
                format(repo, a, b, null, true)
            }
        }
    }

    // ---- remotes

    fun remotes(): List<GitRemote> = withGit { g ->
        val c = g.repository.config
        c.getSubsections("remote").sorted().map { GitRemote(it, c.getString("remote", it, "url").orEmpty()) }
    }

    fun setRemote(name: String, url: String) = withGit { g ->
        val n = name.trim()
        if (!Regex("^[A-Za-z0-9._-]+$").matches(n)) throw GitException(GitMsg.INVALID_REMOTE_NAME)
        val u = normalizeGitUrl(url)
        val c = g.repository.config
        c.setString("remote", n, "url", u)
        if (c.getString("remote", n, "fetch") == null) c.setString("remote", n, "fetch", "+refs/heads/*:refs/remotes/$n/*")
        c.save()
    }

    fun removeRemote(name: String) = withGit { g ->
        val c = g.repository.config
        if (name !in c.getSubsections("remote")) throw GitException(GitMsg.NO_SUCH_REMOTE)
        c.unsetSection("remote", name)
        c.save()
    }

    // ---- network

    fun fetch(auth: GitAuth?, onProgress: (String, Int) -> Unit = { _, _ -> }): String = withGit { g ->
        val remote = defaultRemote(g) ?: throw GitException(GitMsg.NO_REMOTE)
        val cmd = g.fetch().setRemote(remote).setRemoveDeletedRefs(true).setProgressMonitor(monitor(onProgress))
        creds(auth)?.let { cmd.setCredentialsProvider(it) }
        val n = cmd.call().trackingRefUpdates.size
        if (n == 0) GitMsg.UP_TO_DATE else GitMsg.fetched(n)
    }

    fun pull(auth: GitAuth?, onProgress: (String, Int) -> Unit = { _, _ -> }): String = withGit { g ->
        val repo = g.repository
        if (repo.fullBranch?.startsWith(Constants.R_HEADS) != true) throw GitException(GitMsg.DETACHED)
        val branch = repo.branch
        if (repo.resolve(Constants.HEAD) == null) throw GitException(GitMsg.NO_UPSTREAM)
        val bc = BranchConfig(repo.config, branch)
        if (bc.remote == null || bc.remoteTrackingBranch == null) {
            if (defaultRemote(g) == null) throw GitException(GitMsg.NO_REMOTE)
            throw GitException(GitMsg.NO_UPSTREAM)
        }
        val before = repo.resolve(Constants.HEAD)
        val cmd = g.pull().setProgressMonitor(monitor(onProgress))
        creds(auth)?.let { cmd.setCredentialsProvider(it) }
        val r = cmd.call()
        summarize(repo, r.mergeResult, before, "", GitMsg::pullBlocked)
    }

    fun push(
        auth: GitAuth?, remote: String = "origin", branch: String? = null, setUpstream: Boolean = true, force: Boolean = false,
        onProgress: (String, Int) -> Unit = { _, _ -> },
    ): String = withGit { g ->
        val repo = g.repository
        if (remote !in repo.config.getSubsections("remote")) throw GitException(GitMsg.NO_REMOTE)
        val b = branch ?: run {
            if (repo.fullBranch?.startsWith(Constants.R_HEADS) != true) throw GitException(GitMsg.DETACHED)
            repo.branch
        }
        if (repo.exactRef(Constants.R_HEADS + b) == null) throw GitException(GitMsg.NO_COMMITS)
        val cmd = g.push().setRemote(remote).setRefSpecs(RefSpec("${Constants.R_HEADS}$b:${Constants.R_HEADS}$b"))
            .setForce(force).setProgressMonitor(monitor(onProgress))
        creds(auth)?.let { cmd.setCredentialsProvider(it) }
        var allUpToDate = true
        for (res in cmd.call()) for (ru in res.remoteUpdates) {
            when (ru.status) {
                RemoteRefUpdate.Status.OK -> allUpToDate = false
                RemoteRefUpdate.Status.UP_TO_DATE -> {}
                RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD, RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED ->
                    throw GitException(GitMsg.PUSH_REJECTED)
                RemoteRefUpdate.Status.REJECTED_OTHER_REASON, RemoteRefUpdate.Status.REJECTED_NODELETE -> {
                    val why = ru.message.orEmpty()
                    throw GitException(if (why.isBlank()) GitMsg.PUSH_REJECTED_PROTECTED else GitMsg.pushFailed(why))
                }
                else -> throw GitException(GitMsg.pushFailed(ru.status.toString().lowercase().replace('_', ' ')))
            }
        }
        if (setUpstream) {
            val c = repo.config
            c.setString("branch", b, "remote", remote)
            c.setString("branch", b, "merge", Constants.R_HEADS + b)
            c.save()
        }
        if (allUpToDate) GitMsg.EVERYTHING_UP_TO_DATE else GitMsg.pushed(remote, b)
    }

    // ---- identity

    fun getUser(): Pair<String, String>? = withGit { g ->
        val c = g.repository.config
        val n = c.getString("user", null, "name"); val e = c.getString("user", null, "email")
        if (n.isNullOrBlank() || e.isNullOrBlank()) null else n to e
    }

    fun setUser(name: String, email: String) = withGit { g ->
        val c = g.repository.config
        c.setString("user", null, "name", name.trim())
        c.setString("user", null, "email", email.trim())
        c.save()
    }
}

// ---------------------------------------------------------------- .gitignore

object GitIgnore {
    private val lines = listOf(
        "# Gradle / Android", "build/", ".gradle/", "local.properties", "*.apk", "*.aab", "*.keystore", "*.jks",
        ".idea/", "captures/", ".externalNativeBuild/", ".cxx/", "*.iml", ".DS_Store",
        "# AppBuilder", ".appbuilder*", ".appbuilder-logs/",
    )

    fun androidTemplate(): String = lines.joinToString("\n") + "\n"

    private fun file(dir: File) = File(dir, ".gitignore")
    private fun rules(l: List<String>) = l.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    fun read(dir: File): String = file(dir).takeIf { it.exists() }?.readText().orEmpty()

    fun write(dir: File, text: String) { file(dir).writeText(text) }

    /** Creates the file, or appends only the template rules that are missing. Returns whether anything changed. */
    fun ensure(dir: File): Boolean {
        val f = file(dir)
        if (!f.exists()) { f.writeText(androidTemplate()); return true }
        val text = f.readText()
        val have = rules(text.lines()).toSet()
        val missing = rules(lines).filter { it !in have }
        if (missing.isEmpty()) return false
        val sb = StringBuilder(text)
        if (text.isNotEmpty() && !text.endsWith("\n")) sb.append('\n')
        if (!text.lines().any { it.trim() == "# AppBuilder" }) sb.append("\n# AppBuilder\n")
        missing.forEach { sb.append(it).append('\n') }
        f.writeText(sb.toString())
        return true
    }

    fun addPattern(dir: File, pattern: String): Boolean {
        val p = pattern.trim()
        if (p.isEmpty()) return false
        val text = read(dir)
        if (text.lines().any { it.trim() == p }) return false
        file(dir).writeText(text + (if (text.isNotEmpty() && !text.endsWith("\n")) "\n" else "") + p + "\n")
        return true
    }

    /** Checks the project's root .gitignore, including rules on parent directories and negation. */
    fun isIgnored(dir: File, relativePath: String): Boolean {
        val f = file(dir)
        if (!f.exists()) return false
        val node = IgnoreNode().also { n -> f.inputStream().use { n.parse(it) } }
        val parts = relativePath.trim('/').split('/')
        var cur = ""
        for ((i, part) in parts.withIndex()) {
            cur = if (cur.isEmpty()) part else "$cur/$part"
            val last = i == parts.lastIndex
            val isDir = !last || relativePath.endsWith("/") || File(dir, cur).isDirectory
            when (node.checkIgnored(cur, isDir)) {
                true -> return true
                false -> if (last) return false
                null -> {}
            }
        }
        return false
    }
}
