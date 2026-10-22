package dev.appbuilder

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitTest {
    companion object {
        // Isolate from any global git config of the machine running the tests (the app's home has none).
        @org.junit.BeforeClass @JvmStatic fun isolate() {
            val h = java.nio.file.Files.createTempDirectory("githome").toFile()
            System.setProperty("user.home", h.path)
        }
    }
    @get:Rule val tmp = TemporaryFolder()

    private fun repoDir(name: String = "p") = File(tmp.root, name).apply { mkdirs() }
    private fun write(d: File, path: String, text: String) { File(d, path).apply { parentFile.mkdirs(); writeText(text) } }
    private fun commitAll(r: GitRepo, msg: String = "m") { r.stageAll(); r.commit(msg, "T", "t@example.com") }

    @Test fun gitignoreEnsureIsIdempotentAndAppends() {
        val d = repoDir()
        assertTrue(GitIgnore.ensure(d))
        assertFalse(GitIgnore.ensure(d))
        val t = GitIgnore.read(d)
        assertTrue(t.contains("build/")); assertTrue(t.contains(".appbuilder"))
        write(d, ".gitignore", "custom/\n")
        assertTrue(GitIgnore.ensure(d))
        val t2 = GitIgnore.read(d)
        assertTrue(t2.startsWith("custom/")); assertTrue(t2.contains("# AppBuilder")); assertTrue(t2.contains("build/"))
        assertFalse(GitIgnore.ensure(d))
    }

    @Test fun isIgnoredRules() {
        val d = repoDir()
        GitIgnore.write(d, "build/\n*.apk\n!keep.apk\n")
        assertTrue(GitIgnore.isIgnored(d, "build/x.txt"))
        assertTrue(GitIgnore.isIgnored(d, "a.apk"))
        assertFalse(GitIgnore.isIgnored(d, "keep.apk"))
        assertFalse(GitIgnore.isIgnored(d, "src/Main.kt"))
        assertTrue(GitIgnore.addPattern(d, "*.log")); assertFalse(GitIgnore.addPattern(d, "*.log"))
        assertTrue(GitIgnore.isIgnored(d, "x.log"))
    }

    @Test fun statusTransitions() {
        val d = repoDir(); val r = GitRepo.init(d)
        assertTrue(GitRepo.isRepo(d)); assertEquals("main", r.currentBranch())
        write(d, "a.txt", "one\n")
        var s = r.status()
        assertEquals(listOf("a.txt"), s.untracked)
        r.stage(listOf("a.txt")); s = r.status()
        assertEquals(listOf(GitFileChange("a.txt", ChangeKind.ADDED)), s.staged)
        r.commit("first", "T", "t@example.com")
        assertTrue(r.status().clean)
        write(d, "a.txt", "two\n"); s = r.status()
        assertEquals(listOf(GitFileChange("a.txt", ChangeKind.MODIFIED)), s.unstaged)
        r.stage(listOf("a.txt")); s = r.status()
        assertEquals(1, s.staged.size); assertTrue(s.unstaged.isEmpty())
        r.unstage(listOf("a.txt")); s = r.status()
        assertTrue(s.staged.isEmpty()); assertEquals(1, s.unstaged.size)
        File(d, "a.txt").delete()
        assertEquals(ChangeKind.DELETED, r.status().unstaged.single().kind)
    }

    @Test fun unstageBeforeFirstCommit() {
        val d = repoDir(); val r = GitRepo.init(d)
        write(d, "a.txt", "x"); r.stage(listOf("a.txt"))
        r.unstage(listOf("a.txt"))
        val s = r.status()
        assertTrue(s.staged.isEmpty()); assertEquals(listOf("a.txt"), s.untracked)
    }

    @Test fun discardRestoresAndDeletes() {
        val d = repoDir(); val r = GitRepo.init(d)
        write(d, "a.txt", "one\n"); commitAll(r)
        write(d, "a.txt", "changed\n"); write(d, "new.txt", "n")
        r.discard(listOf("a.txt", "new.txt"))
        assertEquals("one\n", File(d, "a.txt").readText()); assertFalse(File(d, "new.txt").exists())
    }

    @Test fun commitErrors() {
        val d = repoDir(); val r = GitRepo.init(d)
        fun msg(block: () -> Unit) = try { block(); null } catch (e: GitException) { e.msg }
        assertEquals(GitMsg.EMPTY_MESSAGE, msg { r.commit("  ", "T", "t@x.com") })
        assertEquals(GitMsg.NO_AUTHOR, msg { r.commit("m", "", "") })
        assertEquals(GitMsg.NOTHING_TO_COMMIT, msg { r.commit("m", "T", "t@x.com") })
        assertEquals(GitMsg.NOTHING_TO_AMEND, msg { r.commit("m", "T", "t@x.com", amend = true) })
    }

    @Test fun logAndAmendAndPathFilter() {
        val d = repoDir(); val r = GitRepo.init(d)
        write(d, "a.txt", "1"); commitAll(r, "add a")
        write(d, "b.txt", "1"); commitAll(r, "add b")
        assertEquals(listOf("add b", "add a"), r.log().map { it.summary })
        assertEquals(listOf("add a"), r.log(path = "a.txt").map { it.summary })
        write(d, "b.txt", "2"); r.stageAll(); r.commit("add b v2", "T", "t@x.com", amend = true)
        assertEquals(listOf("add b v2", "add a"), r.log().map { it.summary })
        assertEquals("T", r.log().first().author)
    }

    @Test fun branches() {
        val d = repoDir(); val r = GitRepo.init(d)
        write(d, "a.txt", "1"); commitAll(r)
        r.createBranch("feature/x")
        assertEquals("feature/x", r.currentBranch())
        r.checkout("main"); assertEquals("main", r.currentBranch())
        val bs = r.branches()
        assertEquals(setOf("main", "feature/x"), bs.map { it.name }.toSet())
        assertTrue(bs.first { it.name == "main" }.current)
        try { r.createBranch("feature/x"); fail() } catch (e: GitException) { assertEquals(GitMsg.branchExists("feature/x"), e.msg) }
        try { r.createBranch("bad name"); fail() } catch (e: GitException) { assertEquals(GitMsg.INVALID_BRANCH, e.msg) }
        try { r.deleteBranch("main"); fail() } catch (e: GitException) { assertEquals(GitMsg.CANNOT_DELETE_CURRENT, e.msg) }
        r.deleteBranch("feature/x")
        assertEquals(listOf("main"), r.branches().map { it.name })
    }

    @Test fun checkoutBlockedByDirtyFile() {
        val d = repoDir(); val r = GitRepo.init(d)
        write(d, "a.txt", "1"); commitAll(r)
        r.createBranch("other")
        write(d, "a.txt", "other\n"); commitAll(r, "other change")
        r.checkout("main")
        write(d, "a.txt", "dirty\n")
        try { r.checkout("other"); fail() } catch (e: GitException) { assertTrue(e.msg, e.msg.contains("a.txt")) }
        assertEquals("main", r.currentBranch())
    }

    @Test fun diffs() {
        val d = repoDir(); val r = GitRepo.init(d)
        write(d, "a.txt", "one\ntwo\n"); commitAll(r, "root")
        val root = r.log().first()
        assertTrue(r.diffCommit(root.id).contains("+one"))
        write(d, "a.txt", "one\nthree\n")
        assertTrue(r.diff().contains("-two")); assertTrue(r.diff().contains("+three"))
        assertEquals("", r.diff(staged = true))
        r.stage(listOf("a.txt"))
        assertEquals("", r.diff()); assertTrue(r.diff(staged = true).contains("+three"))
        write(d, "n.txt", "fresh\n")
        assertFalse(r.diff().contains("fresh"))
        assertTrue(r.diff(path = "n.txt").contains("+fresh"))
        commitAll(r, "second"); File(d, "a.txt").delete()
        assertTrue(r.diff().contains("deleted file"))
        assertTrue(r.diffCommit(r.log().first().id).contains("+three"))
    }

    @Test fun mergeFastForwardAndConflictAndAbort() {
        val d = repoDir(); val r = GitRepo.init(d)
        write(d, "a.txt", "base\n"); commitAll(r)
        r.createBranch("f"); write(d, "b.txt", "b\n"); commitAll(r, "b")
        r.checkout("main")
        assertTrue(r.mergeBranch("f").startsWith("Fast-forward"))
        assertTrue(File(d, "b.txt").exists())
        r.createBranch("c1"); write(d, "a.txt", "c1\n"); commitAll(r, "c1")
        r.checkout("main"); write(d, "a.txt", "main\n"); commitAll(r, "main change")
        assertTrue(r.mergeBranch("c1").startsWith("Merge conflicts in: a.txt"))
        assertTrue(r.isMerging()); assertEquals(listOf("a.txt"), r.status().conflicts)
        r.abortMerge()
        assertFalse(r.isMerging()); assertEquals("main\n", File(d, "a.txt").readText())
    }

    @Test fun remotes() {
        val d = repoDir(); val r = GitRepo.init(d)
        r.setRemote("origin", "https://github.com/u/r")
        assertEquals(listOf(GitRemote("origin", "https://github.com/u/r")), r.remotes())
        r.setRemote("origin", "github.com/u/r2")
        assertEquals("https://github.com/u/r2", r.remotes().single().url)
        r.removeRemote("origin"); assertTrue(r.remotes().isEmpty())
        try { r.setRemote("origin", "git@github.com:u/r.git"); fail() } catch (e: GitException) { assertEquals(GitMsg.SSH_UNSUPPORTED, e.msg) }
    }

    @Test fun cloneFromLocalBarePushAndPull() {
        val seed = repoDir("seed"); val sr = GitRepo.init(seed)
        write(seed, "a.txt", "1\n"); commitAll(sr)
        val bare = File(tmp.root, "remote.git")
        org.eclipse.jgit.api.Git.cloneRepository().setURI(seed.toURI().toString()).setDirectory(bare).setBare(true).call().close()
        var progress = 0
        val c1 = GitRepo.clone(bare.path, File(tmp.root, "c1"), onProgress = { _, _ -> progress++ })
        val c2 = GitRepo.clone(bare.toURI().toString(), File(tmp.root, "c2"))
        assertTrue(progress > 0)
        write(c1.dir, "x.txt", "x\n"); c1.stageAll(); c1.commit("x", "T", "t@x.com")
        c1.push(null)
        assertTrue(c2.pull(null).isNotEmpty())
        assertTrue(File(c2.dir, "x.txt").exists())
        write(c2.dir, "y.txt", "y\n"); c2.stageAll(); c2.commit("y", "T", "t@x.com"); c2.push(null)
        write(c1.dir, "z.txt", "z\n"); c1.stageAll(); c1.commit("z", "T", "t@x.com")
        try { c1.push(null); fail() } catch (e: GitException) { assertEquals(GitMsg.PUSH_REJECTED, e.msg) }
        assertTrue(c1.pull(null).isNotEmpty())
        c1.push(null)
        assertEquals(0, c1.status().ahead)
    }

    @Test fun cloneIntoNonEmptyDirRejectedAndCleanup() {
        val d = repoDir("t"); write(d, "f.txt", "x")
        try { GitRepo.clone("https://github.com/u/r", d); fail() } catch (e: GitException) { assertEquals(GitMsg.TARGET_NOT_EMPTY, e.msg) }
        assertTrue(File(d, "f.txt").exists())
        val missing = File(tmp.root, "nothere")
        try { GitRepo.clone(File(tmp.root, "does-not-exist.git").path, missing); fail() } catch (e: GitException) {}
        assertFalse(missing.exists())
    }

    @Test fun urlHelpers() {
        assertEquals("r", GitRepo.repoNameFromUrl("https://github.com/u/r.git"))
        assertEquals("my-app", GitRepo.repoNameFromUrl("https://github.com/u/my-app/"))
        assertEquals("repo-1abc", GitRepo.repoNameFromUrl("https://github.com/u/1abc"))
        assertEquals("https://github.com/u/r", normalizeGitUrl(" github.com/u/r/ "))
        for (bad in listOf("", "ssh://git@github.com/u/r", "git@github.com:u/r.git", "hello", "https://github.com")) {
            try { normalizeGitUrl(bad); fail(bad) } catch (e: GitException) {}
        }
    }

    @Test fun userConfig() {
        val d = repoDir(); val r = GitRepo.init(d)
        r.setUser("Ann", "ann@x.com")
        assertEquals("Ann" to "ann@x.com", r.getUser())
    }

    @Test fun everyMessageIsTranslated() {
        val consts = GitMsg::class.java.declaredFields.filter { it.type == String::class.java }.map { it.also { f -> f.isAccessible = true }.get(null) as String }
        assertTrue(consts.size > 20)
        val samples = consts + listOf(
            GitMsg.branchExists("x"), GitMsg.branchNotMerged("x"), GitMsg.checkoutBlocked(listOf("a", "b")), GitMsg.pullBlocked(listOf("a")),
            GitMsg.mergeConflicts(listOf("a")), GitMsg.fastForward(2), GitMsg.merged(1), GitMsg.mergedBranch("f"), GitMsg.pushed("origin", "main"),
            GitMsg.pushFailed("boom"), GitMsg.fetched(3), GitMsg.generic("x"),
        )
        for (s in samples) {
            val ok = GitTexts.exact.containsKey(s) || GitTexts.rules.any { it.first.matches(s) }
            assertTrue("untranslated: $s", ok)
        }
        for ((re, t) in GitTexts.rules) {
            val groups = Regex("\\$(\\d)").findAll(t).map { it.groupValues[1].toInt() }.toList()
            val n = re.toPattern().matcher("").groupCount()
            assertTrue("placeholder mismatch in ${re.pattern}", groups.all { it in 1..n })
        }
    }
}
