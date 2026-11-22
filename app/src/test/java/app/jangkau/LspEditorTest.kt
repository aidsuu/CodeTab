package app.jangkau

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.jangkau.core.ExecResult
import app.jangkau.core.LspCompletion
import app.jangkau.core.LspCompletionItem
import app.jangkau.core.LspDiagnostic
import app.jangkau.core.LspPosition
import app.jangkau.core.LspRange
import app.jangkau.core.LspTextEdit
import app.jangkau.editor.EditorSupport
import app.jangkau.editor.LspItem
import app.jangkau.editor.LspLanguage
import app.jangkau.model.HostProfile
import app.jangkau.ui.WorkspaceState
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LspEditorTest {
    private val path = "/home/user/proj/main.py"
    private lateinit var remote: FakeRemote
    private lateinit var ws: WorkspaceState
    private lateinit var ed: CodeEditor
    private lateinit var server: FakeLsp

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun pos(l: Int, c: Int) = LspPosition(l.toUInt(), c.toUInt())

    private fun waitFor(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check()) {
            idle()
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Before fun setUp() {
        remote = FakeRemote()
        remote.file(path, "import os\nos.pa\n")
        remote.execHandler = { cmd ->
            when {
                "__codetab_lsp__" in cmd -> ExecResult(0, "__codetab_lsp__ pyright-langserver\n", "")
                else -> ExecResult(0, "/home/user/proj\n", "")
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        ws = WorkspaceState(remote, HostProfile(name = "t", host = "h", user = "user", keyId = "k", workDir = "~/proj"), scope, Dispatchers.IO)
        ws.open(path)
        waitFor("server") { remote.servers.isNotEmpty() && ws.tabs.firstOrNull()?.lsp?.ready == true }
        server = remote.servers.single()
        val tab = ws.tabs.single()
        ed = EditorSupport.create(
            ApplicationProvider.getApplicationContext(), tab.title, tab.initialText, false, 14f, Typeface.MONOSPACE,
            onChanged = {}, onCursor = { _, _ -> }, lsp = tab.lsp,
        )
        tab.currentText = { ed.text.toString() }
        waitFor("highlighting") { ed.styles != null }
    }

    @Test fun editsAreSentWithLspPositions() {
        ed.text.insert(1, 5, "th")
        ed.text.delete(0, 0, 0, 7)
        ed.text.insert(0, 2, "\nimport sys")
        assertEquals(ed.text.toString(), server.texts[path])
        assertEquals("change $path v4", server.log.last())
    }

    @Test fun completionsAreOfferedAndApplied() {
        server.completionResult = LspCompletion(false, listOf(
            item("path", edit = LspRange(pos(1, 3), pos(1, 5))),
            item("pardir"),
            item("getcwd"),
        ))
        val lang = ed.editorLanguage as LspLanguage
        val publisher = CompletionPublisher(Handler(Looper.getMainLooper()), {}, 1)
        lang.requireAutoComplete(ContentReference(ed.text), CharPosition(1, 5), publisher, Bundle())
        publisher.updateList(true)
        idle()
        val labels = publisher.items.map { it.label.toString() }
        assertEquals(listOf("pardir", "path"), labels)
        assertTrue(server.log.any { it == "complete 1:5" })

        (publisher.items.first { it.label == "path" } as LspItem).performCompletion(ed, ed.text, 1, 5)
        assertEquals("import os\nos.path\n", ed.text.toString())
        assertEquals(ed.text.toString(), server.texts[path])
    }

    @Test fun snippetsAndAutoImportsAreApplied() {
        ed.text.insert(2, 0, "pri")
        val withImport = LspCompletionItem(
            "print", 3u, null, null, "a", "print", "print(\${1:value})\$0", true, null,
            listOf(LspTextEdit(LspRange(pos(0, 0), pos(0, 0)), "import builtins\n")),
        )
        LspItem(withImport, 3, 0).performCompletion(ed, ed.text, 2, 3)
        assertEquals("import builtins\nimport os\nos.pa\nprint(value)", ed.text.toString())
    }

    @Test fun diagnosticsReachTheEditor() {
        val d = LspDiagnostic(LspRange(pos(1, 3), pos(1, 5)), 1u, "unknown attribute", "Pyright", "reportAttributeAccessIssue")
        server.listener!!.onDiagnostics(path, listOf(d))
        waitFor("diagnostics") { ed.diagnostics != null }
        val regions = mutableListOf<io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion>()
        ed.diagnostics!!.queryInRegion(regions, 0, ed.text.length)
        assertEquals(1, regions.size)
        assertEquals(13, regions[0].startIndex)
        assertEquals(15, regions[0].endIndex)
        assertNotNull(regions[0].detail)
    }

    @Test fun triggerCharacterCompletesWithoutPrefix() {
        server.completionResult = LspCompletion(false, listOf(item("path"), item("sep")))
        ed.text.insert(2, 0, "os.")
        val lang = ed.editorLanguage as LspLanguage
        val publisher = CompletionPublisher(Handler(Looper.getMainLooper()), {}, 1)
        lang.requireAutoComplete(ContentReference(ed.text), CharPosition(2, 3), publisher, Bundle())
        publisher.updateList(true)
        idle()
        assertEquals(listOf("path", "sep"), publisher.items.map { it.label.toString() })
        assertTrue(server.log.any { it == "complete 2:3 ." })
    }

    private fun item(label: String, edit: LspRange? = null) = LspCompletionItem(
        label, 6u, "module", null, label, label, label, false, edit, emptyList(),
    )
}
