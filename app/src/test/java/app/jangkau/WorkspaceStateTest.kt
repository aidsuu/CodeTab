package app.jangkau

import app.jangkau.core.ExecResult
import app.jangkau.model.HostProfile
import app.jangkau.ui.WorkspaceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceStateTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private lateinit var remote: FakeRemote
    private lateinit var ws: WorkspaceState

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        remote = FakeRemote()
        remote.file("/home/user/proj/train.py", "print('hi')\n")
        remote.file("/home/user/proj/data/a.csv", "x,y\n")
        remote.file("/home/user/proj/my run.sh", "echo ok\n")
        remote.execHandler = { cmd ->
            when {
                "rev-parse --show-toplevel" in cmd -> ExecResult(0, "/home/user/proj\n", "")
                "status --porcelain" in cmd -> ExecResult(0, "## main...origin/main [ahead 1]\u0000 M train.py\u0000?? my run.sh\u0000", "")
                else -> ExecResult(0, "", "")
            }
        }
        ws = WorkspaceState(remote, HostProfile(name = "t", host = "h", user = "user", keyId = "k", workDir = "~/proj"), scope, dispatcher)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun explorerLoadsAndExpandsLazily() = scope.runTest {
        assertEquals("/home/user/proj", ws.root)
        ws.start(); advanceUntilIdle()
        assertEquals(listOf("data", "my run.sh", "train.py"), ws.rows().map { it.entry.name })
        ws.toggle(ws.rows().first().entry); advanceUntilIdle()
        assertEquals(listOf("data", "a.csv", "my run.sh", "train.py"), ws.rows().map { it.entry.name })
        assertEquals(1, ws.rows()[1].depth)
        ws.collapseAll()
        assertEquals(3, ws.rows().size)
    }

    @Test fun gitStatusIsParsedFromExec() = scope.runTest {
        ws.refreshGit(); advanceUntilIdle()
        assertEquals("/home/user/proj", ws.git.repo)
        val st = ws.git.status!!
        assertEquals("main", st.branch)
        assertEquals(1u, st.ahead)
        assertEquals(listOf("train.py", "my run.sh"), st.changes.map { it.path })
        assertTrue(remote.commands.any { it.startsWith("GIT_TERMINAL_PROMPT=0 LC_ALL=C git -C /home/user/proj status") })
    }

    @Test fun commitRequiresMessageAndQuotesIt() = scope.runTest {
        ws.refreshGit(); advanceUntilIdle()
        ws.gitCommit(); advanceUntilIdle()
        assertEquals("Write a commit message first", ws.message)
        ws.git.commitMessage = "fix: it's done"
        ws.gitCommit(); advanceUntilIdle()
        assertTrue(remote.commands.any { it.endsWith("commit -m 'fix: it'\\''s done'") })
        assertEquals("", ws.git.commitMessage)
    }

    @Test fun openEditSaveAndDetectConflict() = scope.runTest {
        ws.open("/home/user/proj/train.py"); advanceUntilIdle()
        val tab = ws.activeTab!!
        assertEquals("print('hi')\n", tab.initialText)

        var buffer = "print('hello')\n"
        tab.currentText = { buffer }
        tab.dirty = true
        ws.save(); advanceUntilIdle()
        assertEquals("print('hello')\n", remote.text("/home/user/proj/train.py"))
        assertFalse(tab.dirty)

        remote.write("/home/user/proj/train.py", "from job\n".toByteArray())
        buffer = "tablet version\n"
        tab.dirty = true
        ws.save(); advanceUntilIdle()
        assertNotNull(ws.saveConflict)
        assertEquals("from job\n", remote.text("/home/user/proj/train.py"))
        ws.saveConflict = null
        ws.save(tab, overwrite = true); advanceUntilIdle()
        assertEquals("tablet version\n", remote.text("/home/user/proj/train.py"))
    }

    @Test fun binaryAndMissingFilesShowErrors() = scope.runTest {
        remote.files["/home/user/proj/model.pt"] = byteArrayOf(1, 0, 2, 3)
        ws.open("/home/user/proj/model.pt"); advanceUntilIdle()
        assertNull(ws.activeTab)
        assertTrue(ws.message!!.contains("Binary"))
    }

    @Test fun createRenameDelete() = scope.runTest {
        ws.start(); advanceUntilIdle()
        ws.createFile(ws.root, "new.py"); advanceUntilIdle()
        assertNotNull(remote.stat("/home/user/proj/new.py"))
        assertEquals("new.py", ws.activeTab?.title)
        ws.createFile(ws.root, "new.py"); advanceUntilIdle()
        assertTrue(ws.message!!.contains("already exists"))

        val e = ws.rows().first { it.entry.name == "new.py" }.entry
        ws.rename(e, "old.py"); advanceUntilIdle()
        assertNull(remote.stat("/home/user/proj/new.py"))
        assertNull(ws.tabs.firstOrNull { it.path.endsWith("new.py") })

        val data = ws.rows().first { it.entry.name == "data" }.entry
        ws.delete(data); advanceUntilIdle()
        assertFalse(remote.files.keys.any { it.startsWith("/home/user/proj/data/") })
    }

    @Test fun lostConnectionIsFlagged() = scope.runTest {
        remote.isConnected = false
        remote.execHandler = { throw app.jangkau.core.CoreException.Closed("Connection closed") }
        ws.gitPull(); advanceUntilIdle()
        assertTrue(ws.connectionLost)
        ws.replaceRemote(FakeRemote().also { it.execHandler = remote.execHandler })
        assertFalse(ws.connectionLost)
    }

    @Test fun dotFilesHiddenByDefaultAndSearch() = scope.runTest {
        remote.file("/home/user/proj/.env", "SECRET=1\n")
        remote.dir("/home/user/proj/.git")
        ws.start(); advanceUntilIdle()
        assertEquals(listOf("data", "my run.sh", "train.py"), ws.rows().map { it.entry.name })
        ws.prefs.showHidden = true
        assertEquals(listOf(".git", "data", ".env", "my run.sh", "train.py"), ws.rows().map { it.entry.name })
        ws.prefs.showHidden = false

        ws.toggle(ws.rows().first { it.entry.name == "data" }.entry); advanceUntilIdle()
        ws.collapseAll()
        ws.filter = "CSV"
        val hits = ws.rows()
        assertEquals(listOf("a.csv"), hits.map { it.entry.name })
        assertEquals("data", hits[0].hint)
        ws.filter = "env"
        assertTrue(ws.rows().isEmpty())
    }

    @Test fun imagesAndPdfOpenInViewerTabs() = scope.runTest {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0, 0, 0)
        remote.files["/home/user/proj/plot.png"] = png
        remote.files["/home/user/proj/paper.pdf"] = "%PDF-1.4\n\u0000".toByteArray()
        ws.open("/home/user/proj/plot.png"); advanceUntilIdle()
        val img = ws.activeTab!!
        assertEquals(app.jangkau.editor.Languages.Viewer.Image, img.viewer)
        assertTrue(img.readOnly)
        assertTrue(img.file!!.readBytes().contentEquals(png))
        assertEquals(png.size.toLong(), img.size)

        ws.open("/home/user/proj/paper.pdf"); advanceUntilIdle()
        assertEquals(app.jangkau.editor.Languages.Viewer.Pdf, ws.activeTab!!.viewer)
        assertEquals(2, ws.tabs.size)

        val f = img.file!!
        ws.closeTab(img)
        assertFalse(f.exists())
    }

    @Test fun shortcuts() {
        assertTrue(ws.shortcut(ctrl = true, shift = false, key = 'b'))
        assertFalse(ws.sidebarVisible)
        assertTrue(ws.shortcut(ctrl = true, shift = false, key = 'j'))
        assertFalse(ws.terminalVisible)
        assertFalse(ws.shortcut(ctrl = false, shift = false, key = 's'))
        assertFalse(ws.shortcut(ctrl = true, shift = false, key = 'q'))
    }

    private fun withTmux(r: FakeRemote, sessions: String = "codetab-2\ncodetab-5\n") {
        val base = r.execHandler
        r.execHandler = { cmd -> if ("list-sessions" in cmd) ExecResult(0, "motd\n__codetab_tmux__\nwork\n$sessions", "") else base(cmd) }
    }

    @Test fun tmuxSessionsAreRestoredAndNewShellsUseTmux() = scope.runTest {
        withTmux(remote)
        ws.ensureTerminal(); advanceUntilIdle()
        assertEquals(listOf("bash 2", "bash 5"), ws.terminals.map { it.title })
        assertEquals(listOf("codetab-2", "codetab-5"), ws.terminals.map { it.tmuxSession })
        assertTrue(remote.terminalCommands.all { it!!.contains("new-session -A -s codetab-") })
        assertTrue(ws.tmuxAvailable)

        ws.newTerminal(); advanceUntilIdle()
        assertEquals("codetab-1", ws.terminals.last().tmuxSession)
        ws.closeTerminal(ws.terminals.last()); advanceUntilIdle()
        assertTrue(remote.commands.any { "kill-session -t =codetab-1" in it })
        assertEquals(2, ws.terminals.size)
    }

    @Test fun terminalNumbersReuseTheLowestFreeSlot() = scope.runTest {
        withTmux(remote, sessions = "")
        ws.ensureTerminal(); advanceUntilIdle()
        ws.newTerminal(); advanceUntilIdle()
        assertEquals(listOf("bash 1", "bash 2"), ws.terminals.map { it.title })
        ws.closeTerminal(ws.terminals.first()); advanceUntilIdle()
        ws.newTerminal(); advanceUntilIdle()
        assertEquals(listOf("bash 2", "bash 1"), ws.terminals.map { it.title })
        assertEquals("codetab-1", ws.terminals.last().tmuxSession)
    }

    @Test fun tabClosedWhileOfflineIsKilledAfterReconnect() = scope.runTest {
        withTmux(remote, sessions = "")
        ws.ensureTerminal(); advanceUntilIdle()
        ws.markLost()
        ws.closeTerminal(ws.terminals.single()); advanceUntilIdle()
        assertFalse(remote.commands.any { "kill-session" in it })

        val next = FakeRemote()
        withTmux(next, sessions = "")
        ws.replaceRemote(next); advanceUntilIdle()
        assertTrue(next.commands.any { "kill-session -t =codetab-1" in it })
    }

    @Test fun plainShellWhenTmuxIsMissingOrDisabled() = scope.runTest {
        ws.ensureTerminal(); advanceUntilIdle()
        assertEquals(listOf<String?>(null), remote.terminalCommands)
        assertNull(ws.terminals.single().tmuxSession)
        assertFalse(ws.tmuxAvailable)
    }

    @Test fun reconnectReattachesTmuxShells() = scope.runTest {
        withTmux(remote, sessions = "")
        ws.ensureTerminal(); advanceUntilIdle()
        val tab = ws.terminals.single()
        val plain = ws.attachTerminal(app.jangkau.core.Terminal(80u, 24u), "plain")
        var lostCalls = 0
        ws.onConnectionLost = { lostCalls++ }
        ws.markLost(); ws.markLost()
        assertEquals(1, lostCalls)
        tab.onExit("Shell connection lost")

        val next = FakeRemote()
        withTmux(next, sessions = "codetab-1\n")
        ws.replaceRemote(next); advanceUntilIdle()
        assertFalse(ws.connectionLost)
        assertEquals(listOf("codetab-1"), next.terminalCommands.map { it!!.substringAfter("-A -s ").substringBefore(" ") })
        assertNull(tab.exited)
        assertEquals("Previous connection lost", plain.exited)
    }

    @Test fun pingTracksLatencyAndDetectsLoss() = scope.runTest {
        var lost = 0
        ws.onConnectionLost = { lost++ }
        remote.pingMs = 120
        ws.pingOnce()
        assertEquals(120, ws.latencyMs)
        remote.pingMs = null
        ws.pingOnce()
        assertFalse(ws.connectionLost)
        ws.pingOnce()
        assertTrue(ws.connectionLost)
        assertNull(ws.latencyMs)
        assertEquals(1, lost)
    }
}
