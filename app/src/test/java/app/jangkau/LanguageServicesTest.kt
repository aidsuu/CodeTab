package app.jangkau

import app.jangkau.core.ExecResult
import app.jangkau.core.LspCompletion
import app.jangkau.core.LspCompletionItem
import app.jangkau.core.LspDiagnostic
import app.jangkau.core.LspLocation
import app.jangkau.core.LspPosition
import app.jangkau.core.LspRange
import app.jangkau.editor.LspLanguage
import app.jangkau.lsp.LspStatus
import app.jangkau.model.HostProfile
import app.jangkau.ui.UiPrefs
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanguageServicesTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private lateinit var remote: FakeRemote
    private lateinit var ws: WorkspaceState
    private var installed = listOf("pyright-langserver")
    private val prefs = UiPrefs()

    private fun pos(l: Int, c: Int) = LspPosition(l.toUInt(), c.toUInt())
    private fun range(l: Int, a: Int, b: Int) = LspRange(pos(l, a), pos(l, b))

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        remote = FakeRemote()
        remote.file("/home/user/proj/train.py", "import os\nos.pa\n")
        remote.file("/home/user/proj/lib/util.py", "def helper():\n    pass\n")
        remote.file("/home/user/proj/src/a.c", "int main() {}\n")
        remote.file("/home/user/proj/src/b.cpp", "int f() {}\n")
        remote.file("/home/user/proj/notes.txt", "plain\n")
        remote.execHandler = { cmd ->
            when {
                "__codetab_lsp__" in cmd -> ExecResult(0, "motd\n__codetab_py__ /env/bin/python\n" +
                    installed.joinToString("") { "__codetab_lsp__ $it\n" }, "")
                "while :; do for m in" in cmd -> ExecResult(0, "/home/user/proj\n", "")
                else -> ExecResult(0, "", "")
            }
        }
        ws = WorkspaceState(remote, HostProfile(name = "t", host = "h", user = "user", keyId = "k", workDir = "~/proj"),
            scope, dispatcher, prefs = prefs)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun tab(path: String) = ws.tabs.first { it.path == path }

    @Test fun pythonFileStartsPyrightAndSyncsEdits() = scope.runTest {
        ws.open("/home/user/proj/train.py"); advanceUntilIdle()
        val server = remote.servers.single()
        assertTrue(server.command, "pyright-langserver --stdio" in server.command)
        assertTrue(server.command, "/env/bin/python" in server.command)
        assertTrue(server.settings, "\"pythonPath\":\"/env/bin/python\"" in server.settings)
        assertEquals("/home/user/proj", server.root)
        assertEquals(LspStatus.Ready("fake-ls"), ws.lsp.statusFor("python"))
        assertEquals("open /home/user/proj/train.py python v1", server.log.first())

        val doc = tab("/home/user/proj/train.py").lsp!!
        doc.changed(pos(1, 5), pos(1, 5), "th")
        doc.changed(pos(0, 0), pos(0, 7), "")
        assertEquals("os\nos.path\n", server.texts["/home/user/proj/train.py"])
        assertEquals("change /home/user/proj/train.py v3", server.log.last())

        server.completionResult = LspCompletion(false, listOf(item("path")))
        assertEquals("path", doc.completion(1, 7, null) { "" }!!.items.single().label)

        ws.save(tab("/home/user/proj/train.py")); advanceUntilIdle()
        assertEquals("save /home/user/proj/train.py", server.log.last())
        ws.closeTab(tab("/home/user/proj/train.py")); advanceUntilIdle()
        assertEquals(listOf("close /home/user/proj/train.py", "shutdown"), server.log.takeLast(2))
    }

    @Test fun missingServerListsInstallOptions() = scope.runTest {
        installed = emptyList()
        ws.open("/home/user/proj/train.py"); advanceUntilIdle()
        val status = ws.lsp.statusFor("python") as LspStatus.Missing
        assertEquals("pyright", status.candidates.first().id)
        assertTrue(remote.servers.isEmpty())
        ws.open("/home/user/proj/notes.txt"); advanceUntilIdle()
        assertNull(tab("/home/user/proj/notes.txt").lsp)
    }

    @Test fun failingServerFallsBackToTheNextOne() = scope.runTest {
        installed = listOf("pyright-langserver", "pylsp")
        remote.serverFactory = { cmd, _ ->
            if ("pyright" in cmd) throw app.jangkau.core.CoreException.Io("Language server stopped during startup: boom") else FakeLsp()
        }
        ws.open("/home/user/proj/train.py"); advanceUntilIdle()
        assertTrue(remote.servers.single().command.contains("pylsp"))
        assertTrue(ws.lsp.statusFor("python") is LspStatus.Ready)

        installed = listOf("pyright-langserver")
        ws.lsp.retry("python"); advanceUntilIdle()
        val failed = ws.lsp.statusFor("python") as LspStatus.Failed
        assertTrue(failed.message, "boom" in failed.message)
    }

    @Test fun customCommandIsTriedFirst() = scope.runTest {
        prefs.setCustomServer("python", "my-ls --stdio")
        ws.open("/home/user/proj/train.py"); advanceUntilIdle()
        assertTrue(remote.servers.single().command.contains("my-ls --stdio"))
    }

    @Test fun oneServerServesRelatedLanguages() = scope.runTest {
        installed = listOf("clangd")
        ws.open("/home/user/proj/src/a.c"); advanceUntilIdle()
        ws.open("/home/user/proj/src/b.cpp"); advanceUntilIdle()
        val server = remote.servers.single()
        assertEquals(listOf("open /home/user/proj/src/a.c c v1", "open /home/user/proj/src/b.cpp cpp v1"), server.log)
    }

    @Test fun diagnosticsExitAndReconnect() = scope.runTest {
        ws.open("/home/user/proj/train.py"); advanceUntilIdle()
        val server = remote.servers.single()
        val diag = LspDiagnostic(range(1, 0, 2), 1u, "\"pa\" is not a known attribute", "Pyright", null)
        var received: List<LspDiagnostic>? = null
        tab("/home/user/proj/train.py").lsp!!.onDiagnostics = { received = it }
        server.listener!!.onDiagnostics("/home/user/proj/train.py", listOf(diag))
        server.listener!!.onProgress("Indexing · 40%")
        advanceUntilIdle()
        assertEquals(listOf(diag), ws.lsp.diagnostics["/home/user/proj/train.py"])
        assertEquals(listOf(diag), received)
        assertEquals(LspStatus.Ready("fake-ls", "Indexing · 40%"), ws.lsp.statusFor("python"))

        server.listener!!.onExit("Language server exited (code 1): crash")
        advanceUntilIdle()
        assertTrue(ws.lsp.statusFor("python") is LspStatus.Failed)

        val next = FakeRemote().apply { execHandler = remote.execHandler; files.putAll(remote.files); dirs.addAll(remote.dirs) }
        ws.replaceRemote(next); advanceUntilIdle()
        assertEquals("open /home/user/proj/train.py python v1", next.servers.single().log.single())
        assertTrue(ws.lsp.statusFor("python") is LspStatus.Ready)
    }

    @Test fun definitionOpensTheTargetFile() = scope.runTest {
        ws.open("/home/user/proj/train.py"); advanceUntilIdle()
        remote.servers.single().definitionResult = listOf(LspLocation("/home/user/proj/lib/util.py", range(0, 4, 10)))
        ws.goToDefinition(); advanceUntilIdle()
        assertEquals("/home/user/proj/lib/util.py", ws.activeTab!!.path)
        assertEquals(0 to 4, ws.activeTab!!.initialJump)
        ws.showHover(); advanceUntilIdle()
        assertEquals("hover text", ws.hover)
    }

    @Test fun disabledInSettings() = scope.runTest {
        prefs.languageServers = false
        ws.open("/home/user/proj/train.py"); advanceUntilIdle()
        assertEquals(LspStatus.Off, ws.lsp.statusFor("python"))
        assertTrue(remote.servers.isEmpty())
    }

    @Test fun completionsAreRankedByPrefix() {
        val result = LspCompletion(false, listOf(item("Path", "b"), item("path", "z"), item("expanduser", "a"), item("pathsep", "c"), item("join", "a")))
        assertEquals(listOf("pathsep", "path", "Path", "expanduser"), LspLanguage.rank(result, "pa").map { it.first.label })
        assertEquals(5, LspLanguage.rank(result, "").size)
    }

    private fun item(label: String, sort: String = label) = LspCompletionItem(
        label, 6u, null, null, sort, label, label, false, null, emptyList(),
    )
}
