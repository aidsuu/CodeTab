package app.jangkau.remote

import app.jangkau.core.ExecResult
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
    fun openTerminal(cols: Int, rows: Int, listener: TerminalListener): Terminal

    companion object {
        const val MAX_EDIT_BYTES = 4L * 1024 * 1024
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
    override fun openTerminal(cols: Int, rows: Int, listener: TerminalListener): Terminal =
        session.openTerminal(cols.toUShort(), rows.toUShort(), listener)

    override fun close() {
        runCatching { session.disconnect() }
        session.destroy()
    }
}
