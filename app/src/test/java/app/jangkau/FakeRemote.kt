package app.jangkau

import app.jangkau.core.ExecResult
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
    override fun openTerminal(cols: Int, rows: Int, listener: TerminalListener): Terminal = Terminal(cols.toUShort(), rows.toUShort())
    override fun close() { isConnected = false }

    fun text(path: String) = files[path]?.toString(Charsets.UTF_8)
    fun join(a: String, b: String) = pathJoin(a, b)
}
