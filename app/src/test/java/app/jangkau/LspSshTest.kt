package app.jangkau

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.jangkau.core.ConnectConfig
import app.jangkau.core.ConnectListener
import app.jangkau.core.Hop
import app.jangkau.core.Session
import app.jangkau.editor.EditorSupport
import app.jangkau.editor.LspLanguage
import app.jangkau.lsp.LspStatus
import app.jangkau.model.HostProfile
import app.jangkau.remote.RustRemote
import app.jangkau.ui.UiPrefs
import app.jangkau.ui.WorkspaceState
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Runs pyright on a real SSH server through the whole app stack. Needs the variables set by
 * scripts/e2e-sshd.sh plus CODETAB_LSP_PATH pointing at a folder with pyright-langserver.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LspSshTest {
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(what: String, seconds: Int = 60, check: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (!check()) {
            idle()
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test fun pyrightOverSsh() {
        val port = System.getenv("JANGKAU_E2E_PORT")?.toIntOrNull()
        val lspPath = System.getenv("CODETAB_LSP_PATH")
        assumeTrue(port != null && lspPath != null)
        val dir = System.getenv("JANGKAU_E2E_DIR")!!
        val key = File(System.getenv("JANGKAU_E2E_KEY")!!).readText()
        val user = System.getenv("JANGKAU_E2E_USER")!!
        val project = File(dir, "kotlin-lsp").apply { mkdirs() }
        File(project, "main.py").writeText("import os\n\ndef greet(name: str) -> str:\n    return name.upper()\n\nos.pa\n")

        val session = Session.connect(
            ConnectConfig(Hop("127.0.0.1", port!!.toUShort(), user, key, null), null, "$dir/kh_kotlin", 20u),
            object : ConnectListener {
                override fun progress(step: String) {}
                override fun confirmHostKey(host: String, port: UShort, fingerprint: String, previousFingerprint: String?) = true
            },
        )
        val prefs = UiPrefs().apply { setCustomServer("python", "PATH=\"$lspPath:\$PATH\" pyright-langserver --stdio") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val ws = WorkspaceState(RustRemote(session), HostProfile(name = "e2e", host = "127.0.0.1", user = user, keyId = "k",
            workDir = project.path), scope, Dispatchers.IO, prefs = prefs)
        val path = "${project.path}/main.py"
        ws.open(path)
        waitFor("pyright to start") { ws.lsp.statusFor("python") is LspStatus.Ready }

        val tab = ws.tabs.single()
        val ed = EditorSupport.create(
            ApplicationProvider.getApplicationContext(), tab.title, tab.initialText, false, 14f, Typeface.MONOSPACE,
            onChanged = {}, onCursor = { _, _ -> }, lsp = tab.lsp,
        )
        tab.currentText = { ed.text.toString() }
        tab.lsp!!.onDiagnostics?.let { apply -> ws.lsp.diagnostics[path]?.let(apply) }

        waitFor("diagnostics for os.pa") { ws.lsp.diagnostics[path].orEmpty().any { "pa" in it.message } }

        ed.text.insert(5, 5, "th")
        val publisher = CompletionPublisher(Handler(Looper.getMainLooper()), {}, 1)
        val started = System.currentTimeMillis()
        (ed.editorLanguage as LspLanguage).requireAutoComplete(ContentReference(ed.text), CharPosition(5, 7), publisher, Bundle())
        publisher.updateList(true)
        idle()
        val labels = publisher.items.map { it.label.toString() }
        println("completion in ${System.currentTimeMillis() - started} ms: ${labels.take(5)}")
        assertTrue(labels.toString(), "path" in labels)
        waitFor("diagnostics to clear") { ws.lsp.diagnostics[path].orEmpty().none { "\"pa\"" in it.message } }

        tab.cursorLine = 4; tab.cursorCol = 18
        ws.showHover()
        waitFor("hover") { ws.hover != null }
        println("hover: ${ws.hover}")
        assertTrue(ws.hover!!, "upper" in ws.hover!!)

        tab.cursorLine = 6; tab.cursorCol = 1
        ws.goToDefinition()
        waitFor("definition", 30) { ws.tabs.size == 2 }
        println("definition opened ${ws.activeTab!!.path} at ${ws.activeTab!!.initialJump}")
        assertTrue(ws.activeTab!!.path.endsWith(".pyi"))

        ws.lsp.shutdownAll()
        Thread.sleep(500)
        session.disconnect()
    }
}
