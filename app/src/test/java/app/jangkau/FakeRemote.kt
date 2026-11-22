package app.jangkau

import app.jangkau.core.ExecResult
import app.jangkau.core.LspChange
import app.jangkau.core.LspCompletion
import app.jangkau.core.LspListener
import app.jangkau.core.LspLocation
import app.jangkau.core.LspServerInfo
import app.jangkau.core.LspSignature
import app.jangkau.remote.LspConnection
import app.jangkau.core.RemoteEntry
import app.jangkau.core.Terminal
import app.jangkau.core.TerminalListener
import app.jangkau.core.pathJoin
import app.jangkau.core.pathName
import app.jangkau.core.pathParent
import app.jangkau.remote.Remote

class FakeRemote(override val home: String = "/home/user") : Remote {
    override val label = "user@dev.example.com via bastion"
    override var isConnected = true
    val files = LinkedHashMap<String, ByteArray>()
    val mtimes = HashMap<String, Long>()
    val dirs = linkedSetOf("/", home)
    val commands = mutableListOf<String>()
    var execHandler: (String) -> ExecResult = { ExecResult(127, "", "command not found") }
    var clock = 1_700_000_000L
    var pingMs: Int? = 20
    val terminalCommands = mutableListOf<String?>()

    fun file(path: String, text: String) {
        var d = pathParent(path)
        while (d !in dirs) { dirs += d; d = pathParent(d) }
        files[path] = text.toByteArray(); mtimes[path] = clock
    }

    fun dir(path: String) { dirs += path }

    override fun list(dir: String): List<RemoteEntry> {
        val names = (files.keys + dirs).filter { it != dir && pathParent(it) == dir }.toSet()
        return names.map { p -> entry(p)!! }.sortedWith(compareByDescending<RemoteEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    private fun entry(p: String): RemoteEntry? = when {
        p in dirs -> RemoteEntry(p, pathName(p), true, false, 4096u, 0u)
        p in files -> RemoteEntry(p, pathName(p), false, false, files[p]!!.size.toULong(), (mtimes[p] ?: 0).toULong())
        else -> null
    }

    override fun stat(path: String) = entry(path)
    override fun read(path: String, maxBytes: Long) = files[path] ?: throw java.io.FileNotFoundException(path)
    override fun write(path: String, data: ByteArray) { files[path] = data; clock += 5; mtimes[path] = clock }
    override fun mkdir(path: String) { dirs += path }
    override fun rename(from: String, to: String) {
        files.remove(from)?.let { files[to] = it; mtimes[to] = mtimes.remove(from) ?: 0 }
        if (dirs.remove(from)) dirs += to
    }
    override fun delete(path: String, isDir: Boolean) {
        files.keys.removeAll { it == path || it.startsWith("$path/") }
        dirs.removeAll { it == path || it.startsWith("$path/") }
    }
    override fun exec(command: String, timeoutSec: Int): ExecResult { commands += command; return execHandler(command) }
    override fun openTerminal(cols: Int, rows: Int, command: String?, listener: TerminalListener): Terminal {
        if (!isConnected) throw app.jangkau.core.CoreException.Closed("Connection closed")
        terminalCommands += command
        return Terminal(cols.toUShort(), rows.toUShort())
    }
    val servers = mutableListOf<FakeLsp>()
    var serverFactory: (command: String, root: String) -> FakeLsp = { _, _ -> FakeLsp() }

    override fun startLanguageServer(
        command: String, root: String, settingsJson: String, initOptionsJson: String,
        initTimeoutSec: Int, waitForIndex: Boolean, listener: LspListener,
    ): LspConnection {
        if (!isConnected) throw app.jangkau.core.CoreException.Closed("Connection closed")
        val s = serverFactory(command, root)
        s.command = command; s.root = root; s.settings = settingsJson; s.listener = listener
        servers += s
        return s
    }

    override fun ping(timeoutMs: Int): Int = pingMs ?: throw app.jangkau.core.CoreException.Connect("The server did not respond in time")
    override fun close() { isConnected = false }

    fun text(path: String) = files[path]?.toString(Charsets.UTF_8)
    fun join(a: String, b: String) = pathJoin(a, b)
}

class FakeLsp(
    override val info: LspServerInfo = LspServerInfo(
        "fake-ls", "1", listOf("."), listOf("("), incrementalSync = true,
        completion = true, hover = true, definition = true, signatureHelp = true,
    ),
) : LspConnection {
    var command = ""
    var root = ""
    var settings = ""
    var listener: LspListener? = null
    val log = mutableListOf<String>()
    val texts = HashMap<String, String>()
    var completionResult = LspCompletion(false, emptyList())
    var hoverResult: String? = "hover text"
    var definitionResult: List<LspLocation> = emptyList()
    override var isAlive = true

    override fun stderrTail() = ""
    override fun didOpen(path: String, languageId: String, version: Int, text: String) { log += "open $path $languageId v$version"; texts[path] = text }
    override fun didChange(path: String, version: Int, changes: List<LspChange>) {
        log += "change $path v$version"
        var t = texts[path] ?: ""
        for (c in changes) {
            val r = c.range
            t = if (r == null) c.text else {
                val lines = t.split("\n")
                fun off(l: Int, ch: Int) = lines.take(l).sumOf { it.length + 1 } + ch
                val a = off(r.start.line.toInt(), r.start.character.toInt())
                val b = off(r.end.line.toInt(), r.end.character.toInt())
                t.substring(0, a) + c.text + t.substring(b)
            }
        }
        texts[path] = t
    }
    override fun didSave(path: String, text: String) { log += "save $path" }
    override fun didClose(path: String) { log += "close $path" }
    override fun completion(path: String, line: Int, character: Int, trigger: String?, timeoutMs: Int): LspCompletion {
        log += "complete $line:$character${trigger?.let { " $it" } ?: ""}"; return completionResult
    }
    override fun hover(path: String, line: Int, character: Int, timeoutMs: Int) = hoverResult
    override fun definition(path: String, line: Int, character: Int, timeoutMs: Int) = definitionResult
    override fun signatureHelp(path: String, line: Int, character: Int, timeoutMs: Int): LspSignature? = LspSignature("f(a, b)", null, 2u, 3u)
    override fun shutdown() { log += "shutdown"; isAlive = false }
}
