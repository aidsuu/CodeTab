package app.jangkau.lsp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.jangkau.core.LspChange
import app.jangkau.core.LspCompletion
import app.jangkau.core.LspDiagnostic
import app.jangkau.core.LspListener
import app.jangkau.core.LspLocation
import app.jangkau.core.LspPosition
import app.jangkau.core.LspProbe
import app.jangkau.core.LspRange
import app.jangkau.core.LspServerSpec
import app.jangkau.core.LspSignature
import app.jangkau.core.languageServers
import app.jangkau.core.lspLaunchCommand
import app.jangkau.core.lspProbeCommand
import app.jangkau.core.lspRootCommand
import app.jangkau.core.lspServerConfig
import app.jangkau.core.parseLspProbe
import app.jangkau.core.pathParent
import app.jangkau.remote.LspConnection
import app.jangkau.remote.Remote
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface LspStatus {
    data object Off : LspStatus
    data object Unsupported : LspStatus
    data object Detecting : LspStatus
    data class Missing(val candidates: List<LspServerSpec>) : LspStatus
    data class Starting(val name: String) : LspStatus
    data class Ready(val name: String, val progress: String? = null) : LspStatus
    data class Failed(val name: String, val message: String, val candidates: List<LspServerSpec>) : LspStatus
}

/** One open file, as seen by its language server. */
class LspDocument(val path: String, val lspId: String) {
    @Volatile internal var server: LanguageServices.Server? = null
    @Volatile internal var opened = false
    internal var version = 0
    private var pendingFull = false
    internal var cache: CompletionCache? = null

    /** Reads the editor text. Called on the main thread. */
    var text: () -> String = { "" }
    var onDiagnostics: ((List<LspDiagnostic>) -> Unit)? = null

    val ready get() = opened && server?.conn?.isAlive == true
    val triggerCharacters: List<String> get() = server?.conn?.info?.triggerCharacters.orEmpty()
    val signatureTriggers: List<String> get() = server?.conn?.info?.signatureTriggers.orEmpty()

    /** Forwards one editor edit. Positions are UTF-16 offsets, as in LSP. */
    fun changed(start: LspPosition, end: LspPosition, inserted: String) {
        val conn = server?.conn ?: return
        if (!opened) return
        cache = null
        if (conn.info.incrementalSync) {
            version++
            runCatching { conn.didChange(path, version, listOf(LspChange(LspRange(start, end), inserted))) }
        } else {
            pendingFull = true
            server?.services?.scheduleFullSync(this)
        }
    }

    internal fun flushFull(currentText: String) {
        val conn = server?.conn ?: return
        if (!pendingFull || !opened) return
        pendingFull = false
        version++
        runCatching { conn.didChange(path, version, listOf(LspChange(null, currentText))) }
    }

    internal val needsFullSync get() = pendingFull

    /** Blocking; called from the editor's completion thread. */
    fun completion(line: Int, character: Int, trigger: String?, currentText: () -> String): LspCompletion? {
        val conn = server?.conn ?: return null
        if (!opened) return null
        if (pendingFull) flushFull(currentText())
        return runCatching { conn.completion(path, line, character, trigger, COMPLETION_TIMEOUT_MS) }.getOrNull()
    }

    internal fun saved(text: String) {
        val conn = server?.conn ?: return
        if (opened) runCatching { conn.didSave(path, text) }
    }

    companion object {
        const val COMPLETION_TIMEOUT_MS = 6000
    }
}

internal class CompletionCache(val line: Int, val start: Int, val prefix: String, val result: LspCompletion)

class LanguageServices(
    private val remote: () -> Remote,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val enabled: () -> Boolean,
    private val customCommand: (String) -> String?,
    private val workspaceRoot: () -> String,
    private val pythonCommand: String?,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val idleShutdownMs: Long = 5 * 60_000,
) {
    val status = mutableStateMapOf<String, LspStatus>()
    val diagnostics = mutableStateMapOf<String, List<LspDiagnostic>>()
    var message by mutableStateOf<String?>(null)
    var probe by mutableStateOf<LspProbe?>(null)
        private set

    private val docs = LinkedHashMap<String, LspDocument>()
    private val servers = HashMap<String, Server>()
    private val roots = HashMap<String, String>()
    private val failed = HashSet<String>()
    private val probeLock = Mutex()
    private var generation = 0

    inner class Server(val key: String, val spec: LspServerSpec, val root: String) {
        val services get() = this@LanguageServices
        @Volatile var conn: LspConnection? = null
        val docs = LinkedHashSet<String>()
        var progress: String? = null
        var idleJob: Job? = null
        val gen = generation
        val name get() = conn?.info?.name?.takeIf { it != "language server" } ?: spec.name
    }

    fun candidates(lspId: String): List<LspServerSpec> = languageServers().filter { lspId in it.languages }

    private fun custom(lspId: String): LspServerSpec? = customCommand(lspId)?.trim()?.takeIf { it.isNotEmpty() }?.let { cmd ->
        LspServerSpec(
            id = "custom-$lspId", name = "Custom server", languages = listOf(lspId),
            binary = cmd.substringBefore(' '), command = cmd, install = "", rootMarkers = listOf(".git"),
            initTimeoutSecs = 120u, waitForIndex = false,
        )
    }

    fun attach(doc: LspDocument) {
        docs[doc.path] = doc
        if (!enabled()) { status[doc.lspId] = LspStatus.Off; return }
        scope.launch(main) { ensureServer(doc) }
    }

    fun detach(path: String) {
        val doc = docs.remove(path) ?: return
        diagnostics.remove(path)
        val server = doc.server ?: return
        server.docs.remove(path)
        if (doc.opened) runCatching { server.conn?.didClose(path) }
        doc.opened = false
        doc.server = null
        if (server.docs.isEmpty()) scheduleIdleShutdown(server)
    }

    fun saved(path: String, text: String) { docs[path]?.saved(text) }

    fun document(path: String): LspDocument? = docs[path]

    fun statusFor(lspId: String?): LspStatus? = lspId?.let { status[it] }

    /** Drops every server, for example after a reconnect, and starts them again for open files. */
    fun restart(reprobe: Boolean = false) {
        generation++
        val old = servers.values.toList()
        servers.clear()
        roots.clear()
        failed.clear()
        if (reprobe) probe = null
        old.forEach { s -> s.idleJob?.cancel(); s.conn?.let { c -> scope.launch(io) { runCatching { c.shutdown() } } } }
        status.clear()
        docs.values.forEach { d ->
            d.server = null
            d.opened = false
            d.cache = null
            if (enabled()) scope.launch(main) { ensureServer(d) } else status[d.lspId] = LspStatus.Off
        }
    }

    fun retry(lspId: String) {
        generation++
        probe = null
        failed.clear()
        roots.clear()
        val affected = docs.values.filter { it.lspId == lspId }
        affected.forEach { d ->
            d.server?.let { s -> s.docs.remove(d.path); if (s.docs.isEmpty()) stop(s) }
            d.server = null
            d.opened = false
        }
        status.remove(lspId)
        affected.forEach { d -> scope.launch(main) { ensureServer(d) } }
    }

    fun shutdownAll() {
        generation++
        val old = servers.values.toList()
        servers.clear()
        old.forEach { s -> s.idleJob?.cancel(); s.conn?.let { c -> scope.launch(io) { runCatching { c.shutdown() } } } }
    }

    private fun stop(server: Server) {
        if (servers[server.key] === server) servers.remove(server.key)
        server.idleJob?.cancel()
        server.conn?.let { c -> scope.launch(io) { runCatching { c.shutdown() } } }
    }

    private fun scheduleIdleShutdown(server: Server) {
        server.idleJob?.cancel()
        server.idleJob = scope.launch(main) {
            delay(idleShutdownMs)
            if (server.docs.isEmpty()) stop(server)
        }
    }

    internal fun scheduleFullSync(doc: LspDocument) {
        scope.launch(main) {
            delay(FULL_SYNC_DELAY_MS)
            if (doc.needsFullSync && docs[doc.path] === doc) doc.flushFull(doc.text())
        }
    }

    private suspend fun probeOnce(): LspProbe = probeLock.withLock {
        probe?.let { return it }
        val bins = languageServers().map { it.binary }.distinct()
        val out = withContext(io) {
            runCatching { remote().exec(lspProbeCommand(bins, pythonCommand), 60).stdout }.getOrDefault("")
        }
        parseLspProbe(out).also { probe = it }
    }

    private suspend fun rootFor(spec: LspServerSpec, dir: String): String {
        val key = "${spec.id}|$dir"
        roots[key]?.let { return it }
        val ws = workspaceRoot()
        val found = withContext(io) {
            runCatching { remote().exec(lspRootCommand(dir, spec.rootMarkers, ws), 15).stdout.trim() }.getOrNull()
        }?.takeIf { it.startsWith("/") } ?: if (dir.startsWith(ws)) ws else dir
        roots[key] = found
        return found
    }

    private suspend fun ensureServer(doc: LspDocument) {
        val gen = generation
        if (docs[doc.path] !== doc || doc.server != null) return
        if (status[doc.lspId] !is LspStatus.Ready) status[doc.lspId] = LspStatus.Detecting
        val probe = probeOnce()
        if (gen != generation || docs[doc.path] !== doc) return
        val known = candidates(doc.lspId)
        val usable = listOfNotNull(custom(doc.lspId)) + known.filter { it.binary in probe.binaries }
        if (usable.isEmpty()) {
            status[doc.lspId] = if (known.isEmpty()) LspStatus.Unsupported else LspStatus.Missing(known)
            return
        }
        var lastError: Pair<String, String>? = null
        for (spec in usable) {
            val root = rootFor(spec, pathParent(doc.path))
            if (gen != generation || docs[doc.path] !== doc) return
            val key = "${spec.id}|$root"
            if (key in failed) continue
            servers[key]?.let { existing ->
                existing.idleJob?.cancel()
                existing.docs += doc.path
                doc.server = existing
                if (existing.conn != null) open(existing, doc)
                return
            }
            val server = Server(key, spec, root)
            servers[key] = server
            server.docs += doc.path
            doc.server = server
            status[doc.lspId] = LspStatus.Starting(spec.name)
            val cfg = lspServerConfig(spec.id, probe.python)
            val result = withContext(io) {
                runCatching {
                    remote().startLanguageServer(
                        lspLaunchCommand(spec.command, root, probe.python), root,
                        cfg.settingsJson, cfg.initOptionsJson, spec.initTimeoutSecs.toInt(), spec.waitForIndex, listener(server),
                    )
                }
            }
            if (gen != generation || servers[key] !== server) {
                result.getOrNull()?.let { c -> scope.launch(io) { runCatching { c.shutdown() } } }
                return
            }
            val conn = result.getOrNull()
            if (conn != null) {
                server.conn = conn
                server.docs.toList().forEach { p -> docs[p]?.let { open(server, it) } }
                return
            }
            servers.remove(key)
            failed += key
            lastError = spec.name to (result.exceptionOrNull()?.message ?: "Could not start")
            val others = server.docs.filter { it != doc.path }
            server.docs.clear()
            doc.server = null
            others.forEach { p -> docs[p]?.let { d -> d.server = null; scope.launch(main) { ensureServer(d) } } }
        }
        val (name, msg) = lastError ?: return
        status[doc.lspId] = LspStatus.Failed(name, msg, known)
    }

    private fun open(server: Server, doc: LspDocument) {
        val conn = server.conn ?: return
        if (doc.opened) return
        doc.version = 1
        doc.cache = null
        runCatching { conn.didOpen(doc.path, doc.lspId, doc.version, doc.text()) }
        doc.opened = true
        status[doc.lspId] = LspStatus.Ready(server.name, server.progress)
    }

    private fun languagesOf(server: Server) = server.docs.mapNotNull { docs[it]?.lspId }.toSet()

    private fun listener(server: Server) = object : LspListener {
        override fun onDiagnostics(path: String, diagnostics: List<LspDiagnostic>) {
            scope.launch(main) {
                if (server.gen != generation) return@launch
                val doc = docs[path] ?: return@launch
                this@LanguageServices.diagnostics[path] = diagnostics
                doc.onDiagnostics?.invoke(diagnostics)
            }
        }

        override fun onProgress(message: String?) {
            scope.launch(main) {
                if (server.gen != generation) return@launch
                server.progress = message
                languagesOf(server).forEach { id ->
                    if (status[id] is LspStatus.Ready) status[id] = LspStatus.Ready(server.name, message)
                }
            }
        }

        override fun onMessage(severity: UByte, message: String) {
            if (severity.toInt() != 1) return
            scope.launch(main) { if (server.gen == generation) this@LanguageServices.message = "${server.name}: $message" }
        }

        override fun onExit(message: String) {
            scope.launch(main) {
                if (server.gen != generation || servers[server.key] !== server) return@launch
                servers.remove(server.key)
                val affected = server.docs.mapNotNull { docs[it] }
                affected.forEach { it.server = null; it.opened = false }
                val connected = runCatching { remote().isConnected }.getOrDefault(false)
                if (!connected) return@launch
                affected.map { it.lspId }.toSet().forEach { id ->
                    status[id] = LspStatus.Failed(server.spec.name, message, candidates(id))
                }
            }
        }
    }

    suspend fun hover(doc: LspDocument, line: Int, col: Int): String? = request(doc) { c ->
        if (c.info.hover) c.hover(doc.path, line, col, REQUEST_TIMEOUT_MS) else null
    }

    suspend fun definition(doc: LspDocument, line: Int, col: Int): List<LspLocation> = request(doc) { c ->
        if (c.info.definition) c.definition(doc.path, line, col, REQUEST_TIMEOUT_MS) else emptyList()
    } ?: emptyList()

    suspend fun signature(doc: LspDocument, line: Int, col: Int): LspSignature? = request(doc) { c ->
        if (c.info.signatureHelp) c.signatureHelp(doc.path, line, col, REQUEST_TIMEOUT_MS) else null
    }

    private suspend fun <T> request(doc: LspDocument, block: (LspConnection) -> T?): T? {
        val conn = doc.server?.conn ?: return null
        if (!doc.opened) return null
        if (doc.needsFullSync) doc.flushFull(doc.text())
        return withContext(io) { runCatching { block(conn) }.getOrNull() }
    }

    companion object {
        const val REQUEST_TIMEOUT_MS = 8000
        const val FULL_SYNC_DELAY_MS = 250L
    }
}
