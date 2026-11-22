package app.jangkau.remote

import app.jangkau.core.ExecResult
import app.jangkau.core.LanguageServer
import app.jangkau.core.LspChange
import app.jangkau.core.LspCompletion
import app.jangkau.core.LspListener
import app.jangkau.core.LspLocation
import app.jangkau.core.LspServerInfo
import app.jangkau.core.LspSignature
import app.jangkau.core.RemoteEntry
import app.jangkau.core.Session
import app.jangkau.core.Terminal
import app.jangkau.core.TerminalListener
import java.io.Closeable

interface Remote : Closeable {
    val label: String
    val home: String
    val isConnected: Boolean

    fun list(dir: String): List<RemoteEntry>
    fun stat(path: String): RemoteEntry?
    fun read(path: String, maxBytes: Long = MAX_EDIT_BYTES): ByteArray
    fun write(path: String, data: ByteArray)
    fun mkdir(path: String)
    fun rename(from: String, to: String)
    fun delete(path: String, isDir: Boolean)
    fun exec(command: String, timeoutSec: Int = 120): ExecResult
    fun openTerminal(cols: Int, rows: Int, command: String?, listener: TerminalListener): Terminal
    fun ping(timeoutMs: Int): Int
    fun startLanguageServer(
        command: String,
        root: String,
        settingsJson: String,
        initOptionsJson: String,
        initTimeoutSec: Int,
        waitForIndex: Boolean,
        listener: LspListener,
    ): LspConnection

    companion object {
        const val MAX_EDIT_BYTES = 4L * 1024 * 1024
    }
}

interface LspConnection {
    val info: LspServerInfo
    val isAlive: Boolean
    fun stderrTail(): String
    fun didOpen(path: String, languageId: String, version: Int, text: String)
    fun didChange(path: String, version: Int, changes: List<LspChange>)
    fun didSave(path: String, text: String)
    fun didClose(path: String)
    fun completion(path: String, line: Int, character: Int, trigger: String?, timeoutMs: Int): LspCompletion
    fun hover(path: String, line: Int, character: Int, timeoutMs: Int): String?
    fun definition(path: String, line: Int, character: Int, timeoutMs: Int): List<LspLocation>
    fun signatureHelp(path: String, line: Int, character: Int, timeoutMs: Int): LspSignature?
    fun shutdown()
}

class RustLsp(private val ls: LanguageServer) : LspConnection {
    override val info: LspServerInfo = ls.info()
    override val isAlive: Boolean get() = ls.isAlive()
    override fun stderrTail() = ls.stderrTail()
    override fun didOpen(path: String, languageId: String, version: Int, text: String) = ls.didOpen(path, languageId, version, text)
    override fun didChange(path: String, version: Int, changes: List<LspChange>) = ls.didChange(path, version, changes)
    override fun didSave(path: String, text: String) = ls.didSave(path, text)
    override fun didClose(path: String) = ls.didClose(path)
    override fun completion(path: String, line: Int, character: Int, trigger: String?, timeoutMs: Int) =
        ls.completion(path, line.toUInt(), character.toUInt(), trigger, timeoutMs.toUInt())
    override fun hover(path: String, line: Int, character: Int, timeoutMs: Int) =
        ls.hover(path, line.toUInt(), character.toUInt(), timeoutMs.toUInt())
    override fun definition(path: String, line: Int, character: Int, timeoutMs: Int) =
        ls.definition(path, line.toUInt(), character.toUInt(), timeoutMs.toUInt())
    override fun signatureHelp(path: String, line: Int, character: Int, timeoutMs: Int) =
        ls.signatureHelp(path, line.toUInt(), character.toUInt(), timeoutMs.toUInt())
    override fun shutdown() {
        runCatching { ls.shutdown() }
        ls.destroy()
    }
}

val ExecResult.ok get() = exitCode == 0

val ExecResult.combined
    get() = listOf(stdout.trimEnd(), stderr.trimEnd()).filter { it.isNotEmpty() }.joinToString("\n")

class RustRemote(private val session: Session) : Remote {
    override val label: String = session.label()
    override val home: String = session.home()
    override val isConnected: Boolean get() = session.isConnected()

    override fun list(dir: String) = session.list(dir)
    override fun stat(path: String) = session.stat(path)
    override fun read(path: String, maxBytes: Long) = session.read(path, maxBytes.toULong())
    override fun write(path: String, data: ByteArray) = session.write(path, data)
    override fun mkdir(path: String) = session.mkdir(path)
    override fun rename(from: String, to: String) = session.rename(from, to)
    override fun delete(path: String, isDir: Boolean) = session.delete(path, isDir)
    override fun exec(command: String, timeoutSec: Int) = session.exec(command, timeoutSec.toUInt())
    override fun openTerminal(cols: Int, rows: Int, command: String?, listener: TerminalListener): Terminal =
        session.openTerminal(cols.toUShort(), rows.toUShort(), command, listener)
    override fun ping(timeoutMs: Int): Int = session.ping(timeoutMs.toUInt()).toInt()
    override fun startLanguageServer(
        command: String,
        root: String,
        settingsJson: String,
        initOptionsJson: String,
        initTimeoutSec: Int,
        waitForIndex: Boolean,
        listener: LspListener,
    ): LspConnection = RustLsp(
        session.startLanguageServer(command, root, settingsJson, initOptionsJson, initTimeoutSec.toUInt(), waitForIndex, listener)
    )

    override fun close() {
        runCatching { session.disconnect() }
        session.destroy()
    }
}
