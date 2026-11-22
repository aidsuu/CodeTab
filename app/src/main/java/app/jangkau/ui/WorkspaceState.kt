package app.jangkau.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.jangkau.core.CoreException
import app.jangkau.core.GitChange
import app.jangkau.core.GitOp
import app.jangkau.core.GitStatus
import app.jangkau.core.Mods
import app.jangkau.core.RemoteEntry
import app.jangkau.core.TermKey
import app.jangkau.core.TermSnapshot
import app.jangkau.core.Terminal
import app.jangkau.core.TerminalListener
import app.jangkau.core.encodePaste
import app.jangkau.core.gitCommand
import app.jangkau.core.parseGitStatus
import app.jangkau.core.pathExpandHome
import app.jangkau.core.pathJoin
import app.jangkau.core.pathName
import app.jangkau.core.pathParent
import app.jangkau.core.pathRelativeTo
import app.jangkau.core.shQuote
import app.jangkau.core.parseTmuxProbe
import app.jangkau.core.tmuxAttachCommand
import app.jangkau.core.tmuxKillCommand
import app.jangkau.core.tmuxProbeCommand
import app.jangkau.core.LspSignature
import app.jangkau.editor.Languages
import app.jangkau.lsp.LanguageServices
import app.jangkau.lsp.LspDocument
import app.jangkau.model.HostProfile
import app.jangkau.remote.Remote
import app.jangkau.remote.combined
import app.jangkau.remote.ok
import app.jangkau.term.TerminalSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SidePanel { Explorer, Git }

class EditorTab(
    val key: String,
    val path: String,
    val title: String,
    val initialText: String,
    mtime: Long,
    val readOnly: Boolean = false,
    val language: String = Languages.forName(title).label,
    val viewer: Languages.Viewer? = null,
    val file: java.io.File? = null,
    val size: Long = 0,
) {
    var mtime by mutableStateOf(mtime)
    var dirty by mutableStateOf(false)
    var cursorLine by mutableIntStateOf(1)
    var cursorCol by mutableIntStateOf(1)
    var currentText: (() -> String)? = null
    var undo: (() -> Unit)? = null
    var redo: (() -> Unit)? = null
    var saving by mutableStateOf(false)
    var lsp: LspDocument? = null
    var initialJump: Pair<Int, Int>? = null
    var jumpTo: ((line: Int, col: Int) -> Unit)? = null
    var requestCompletion: (() -> Unit)? = null

    fun text(): String = currentText?.invoke() ?: initialText
}

class TermTab(val id: Int, terminal: Terminal, val tmuxSession: String? = null) : TerminalSource, TerminalListener {
    var terminal by mutableStateOf(terminal)
        private set
    var title by mutableStateOf("bash")
    var stickyCtrl by mutableStateOf(false)
    var stickyAlt by mutableStateOf(false)
    var exited by mutableStateOf<String?>(null)
    var endedCleanly = false
        private set
    @Volatile var onUpdate: (() -> Unit)? = null
    @Volatile private var generation = 0

    fun listener(): TerminalListener {
        val gen = generation
        return object : TerminalListener {
            override fun onUpdate() { if (gen == generation) this@TermTab.onUpdate() }
            override fun onExit(message: String?) { if (gen == generation) this@TermTab.onExit(message) }
        }
    }

    val firstGeneration get() = generation == 0

    fun nextListener(): TerminalListener {
        generation++
        return listener()
    }

    fun reattach(t: Terminal) {
        terminal = t
        exited = null
        endedCleanly = false
        onUpdate?.invoke()
    }

    override fun snapshot(scrollOffset: Int): TermSnapshot = terminal.snapshot(scrollOffset.toUInt())
    override fun send(bytes: ByteArray) { terminal.send(bytes) }
    override fun typeText(text: String, mods: Mods) { terminal.typeText(text, mods) }
    override fun typeKey(key: TermKey, mods: Mods) { terminal.typeKey(key, mods) }
    override fun resize(cols: Int, rows: Int) = terminal.resize(cols.toUShort(), rows.toUShort())
    override fun consumeMods(): Mods {
        val m = Mods(stickyCtrl, stickyAlt, false)
        if (stickyCtrl) stickyCtrl = false
        if (stickyAlt) stickyAlt = false
        return m
    }

    fun type(text: String) { terminal.clearPredictions(); terminal.send(text.toByteArray()) }
    fun paste(text: String) { terminal.clearPredictions(); terminal.send(encodePaste(text, terminal.snapshot(0u).bracketedPaste)) }

    override fun onUpdate() { onUpdate?.invoke() }
    override fun onExit(message: String?) {
        endedCleanly = message?.startsWith("Shell exited") == true
        exited = message ?: "Shell exited"
        onUpdate?.invoke()
    }
}

class GitState {
    var repo by mutableStateOf<String?>(null)
    var status by mutableStateOf<GitStatus?>(null)
    var notRepo by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var output by mutableStateOf("")
    var commitMessage by mutableStateOf("")
}

data class SaveConflict(val tab: EditorTab)

data class TreeRow(val entry: RemoteEntry, val depth: Int, val expanded: Boolean, val loading: Boolean, val hint: String? = null)

class WorkspaceState(
    remote: Remote,
    val profile: HostProfile,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    val prefs: UiPrefs = UiPrefs(),
    private val cacheDir: java.io.File = java.io.File(System.getProperty("java.io.tmpdir"), "codetab-preview"),
) {
    var remote by mutableStateOf(remote)
        private set
    var root by mutableStateOf(pathExpandHome(profile.workDir, remote.home))
        private set
    private val children = mutableStateMapOf<String, List<RemoteEntry>>()
    private val expanded = mutableStateMapOf<String, Boolean>()
    private val loading = mutableStateMapOf<String, Boolean>()
    var selectedPath by mutableStateOf<String?>(null)

    val tabs = mutableStateListOf<EditorTab>()
    var activeKey by mutableStateOf<String?>(null)
    val activeTab get() = tabs.firstOrNull { it.key == activeKey }

    var panel by mutableStateOf(SidePanel.Explorer)
    var sidebarVisible by mutableStateOf(true)
    var terminalVisible by mutableStateOf(true)
    var terminalMaximized by mutableStateOf(false)
    val terminals = mutableStateListOf<TermTab>()
    var activeTerminal by mutableIntStateOf(0)
    private var nextTermId = 1

    var message by mutableStateOf<String?>(null)
    var saveConflict by mutableStateOf<SaveConflict?>(null)
    var connectionLost by mutableStateOf(false)
        private set
    var reconnectStatus by mutableStateOf<String?>(null)
    var latencyMs by mutableStateOf<Int?>(null)
    var tmuxAvailable by mutableStateOf(false)
        private set
    var onConnectionLost: (() -> Unit)? = null
    private var pingFailures = 0
    private var tmuxProbed = false
    private val probeLock = Mutex()
    var editorTextSp: Float
        get() = prefs.editorTextSp
        set(v) { prefs.editorTextSp = v }
    var filter by mutableStateOf("")
    val git = GitState()
    val lsp = LanguageServices(
        remote = { this.remote }, scope = scope, io = io,
        enabled = { prefs.languageServers }, customCommand = { prefs.customServer(it) },
        workspaceRoot = { root }, pythonCommand = profile.pythonCmd,
    )
    var hover by mutableStateOf<String?>(null)
    var signature by mutableStateOf<LspSignature?>(null)
    private var signatureLine = -1

    private fun visible(e: RemoteEntry) = prefs.showHidden || !e.name.startsWith(".")

    fun rows(): List<TreeRow> {
        val out = ArrayList<TreeRow>()
        val q = filter.trim()
        if (q.isNotEmpty()) {
            fun search(dir: String) {
                for (e in children[dir].orEmpty()) {
                    if (!visible(e)) continue
                    if (e.name.contains(q, ignoreCase = true)) {
                        out += TreeRow(e, 0, false, false, hint = pathRelativeTo(pathParent(e.path), root)?.takeIf { it != "." })
                    }
                    if (e.isDir) search(e.path)
                }
            }
            search(root)
            return out
        }
        fun walk(dir: String, depth: Int) {
            for (e in children[dir].orEmpty()) {
                if (!visible(e)) continue
                val open = e.isDir && expanded[e.path] == true
                out += TreeRow(e, depth, open, loading[e.path] == true)
                if (open) walk(e.path, depth + 1)
            }
        }
        walk(root, 0)
        return out
    }

    val rootLoaded get() = children.containsKey(root)

    fun start() {
        refreshDir(root)
        refreshGit()
    }

    fun markLost() {
        if (connectionLost) return
        connectionLost = true
        latencyMs = null
        onConnectionLost?.invoke()
    }

    fun replaceRemote(newRemote: Remote) {
        val old = remote
        remote = newRemote
        connectionLost = false
        reconnectStatus = null
        latencyMs = null
        pingFailures = 0
        val keep = terminals.filter { it.tmuxSession != null && !it.endedCleanly }
        terminals.forEach { if (it !in keep && it.exited == null) it.exited = "Previous connection lost" }
        scope.launch(io) { runCatching { old.close() } }
        refreshAll()
        lsp.restart()
        keep.forEach { tab ->
            val listener = tab.nextListener()
            val size = tab.terminal.size()
            launchIo("Could not reattach ${tab.title}") {
                val t = newRemote.openTerminal(size[0].toInt(), size[1].toInt(), tmuxAttachCommand(tab.tmuxSession!!), listener)
                configure(t)
                withMain { tab.reattach(t) }
            }
        }
        message = "Reconnected to ${newRemote.label}"
    }

    suspend fun pingOnce() {
        if (connectionLost) return
        val target = remote
        val result = withContext(io) {
            runCatching { target.ping(PING_TIMEOUT_MS) }.onFailure {
                if (!runCatching { target.isConnected }.getOrDefault(false)) pingFailures = PING_MAX_FAILURES
            }
        }
        if (target !== remote || connectionLost) return
        result.onSuccess { ms ->
            pingFailures = 0
            latencyMs = ms
            terminals.forEach { it.terminal.setLatency(ms.toUInt()) }
        }.onFailure {
            pingFailures++
            if (pingFailures >= PING_MAX_FAILURES) markLost()
        }
    }

    fun setPredictiveEcho(on: Boolean) {
        prefs.predictiveEcho = on
        terminals.forEach { it.terminal.setPrediction(on) }
    }

    private fun configure(t: Terminal) {
        t.setPrediction(prefs.predictiveEcho)
        latencyMs?.let { t.setLatency(it.toUInt()) }
    }

    fun openFolder(path: String) {
        val p = pathExpandHome(path.trim().ifEmpty { "~" }, remote.home)
        root = p
        expanded.clear()
        children.clear()
        refreshDir(p)
        refreshGit()
    }

    fun refreshDir(dir: String) {
        loading[dir] = true
        launchIo("Could not read the folder") {
            val list = remote.list(dir)
            withMain { children[dir] = list }
        }.invokeOnCompletion { loading.remove(dir) }
    }

    fun refreshAll() {
        refreshDir(root)
        expanded.filterValues { it }.keys.forEach { refreshDir(it) }
        refreshGit()
    }

    fun toggle(entry: RemoteEntry) {
        selectedPath = entry.path
        if (!entry.isDir) { open(entry.path); return }
        val now = expanded[entry.path] != true
        expanded[entry.path] = now
        if (now && !children.containsKey(entry.path)) refreshDir(entry.path)
    }

    fun collapseAll() = expanded.clear()

    fun preload(dir: String, entries: List<RemoteEntry>, open: Boolean = false) {
        children[dir] = entries
        if (open) expanded[dir] = true
    }

    fun createFile(dir: String, name: String) = launchIo("Could not create the file") {
        val path = pathJoin(dir, name)
        if (remote.stat(path) != null) throw IllegalStateException("$name already exists")
        remote.write(path, ByteArray(0))
        withMain { expanded[dir] = true }
        reloadDir(dir)
        withMain { open(path) }
    }

    fun createFolder(dir: String, name: String) = launchIo("Could not create the folder") {
        remote.mkdir(pathJoin(dir, name))
        withMain { expanded[dir] = true }
        reloadDir(dir)
    }

    fun rename(entry: RemoteEntry, newName: String) = launchIo("Could not rename") {
        val target = pathJoin(pathParent(entry.path), newName)
        remote.rename(entry.path, target)
        reloadDir(pathParent(entry.path))
        withMain {
            tabs.filter { it.path == entry.path }.forEach { closeTab(it, force = true) }
        }
    }

    fun delete(entry: RemoteEntry) = launchIo("Could not delete") {
        remote.delete(entry.path, entry.isDir)
        reloadDir(pathParent(entry.path))
        withMain {
            tabs.filter { it.path == entry.path || pathRelativeTo(it.path, entry.path) != null }
                .forEach { closeTab(it, force = true) }
            message = "${entry.name} deleted"
        }
        refreshGit()
    }

    private suspend fun reloadDir(dir: String) {
        val list = remote.list(dir)
        withMain { children[dir] = list }
    }

    fun open(path: String, jump: Pair<Int, Int>? = null) {
        tabs.firstOrNull { it.key == path }?.let { t ->
            activeKey = t.key
            if (jump != null) t.jumpTo?.invoke(jump.first, jump.second) ?: run { t.initialJump = jump }
            return
        }
        val viewer = Languages.viewer(pathName(path))
        if (viewer != null) { openMedia(path, viewer); return }
        launchIo("Could not open the file") {
            val st = remote.stat(path)
            val bytes = remote.read(path, Remote.MAX_EDIT_BYTES)
            if (bytes.take(8000).any { it == 0.toByte() }) throw IllegalStateException("Binary files cannot be opened in the editor")
            val text = bytes.toString(Charsets.UTF_8)
            withMain {
                val tab = EditorTab(path, path, pathName(path), text, st?.mtime?.toLong() ?: 0L)
                tab.initialJump = jump
                tabs += tab
                activeKey = tab.key
                attachLsp(tab)
            }
        }
    }

    private fun openMedia(path: String, viewer: Languages.Viewer) = launchIo("Could not open ${pathName(path)}") {
        val st = remote.stat(path)
        val bytes = remote.read(path, MAX_MEDIA_BYTES)
        cacheDir.mkdirs()
        val ext = pathName(path).substringAfterLast('.', "bin")
        val local = java.io.File(cacheDir, "${Integer.toHexString(path.hashCode())}-${st?.mtime ?: 0u}.$ext")
        local.writeBytes(bytes)
        withMain {
            val tab = EditorTab(path, path, pathName(path), "", st?.mtime?.toLong() ?: 0L, readOnly = true,
                language = if (viewer == Languages.Viewer.Pdf) "PDF" else "Image", viewer = viewer, file = local, size = bytes.size.toLong())
            tabs += tab
            activeKey = tab.key
        }
    }

    fun openReadOnly(key: String, title: String, text: String) {
        tabs.removeAll { it.key == key }
        val tab = EditorTab(key, key, title, text, 0, readOnly = true, language = Languages.forName(title).label)
        tabs += tab
        activeKey = key
    }

    internal fun attachLsp(tab: EditorTab) {
        if (tab.readOnly || tab.viewer != null) return
        val id = Languages.forName(tab.title).lspId ?: return
        val doc = LspDocument(tab.path, id).also { it.text = { tab.text() } }
        tab.lsp = doc
        lsp.attach(doc)
    }

    private fun detachLsp(tab: EditorTab) {
        if (tab.lsp != null) lsp.detach(tab.path)
        tab.lsp = null
        if (activeKey == tab.key) signature = null
    }

    fun showHover() {
        val tab = activeTab ?: return
        val doc = tab.lsp ?: run { hover = "No language server for this file"; return }
        val (line, col) = tab.cursorLine - 1 to tab.cursorCol - 1
        scope.launch(Dispatchers.Main.immediate) {
            hover = lsp.hover(doc, line, col) ?: "No information at the cursor"
        }
    }

    fun goToDefinition() {
        val tab = activeTab ?: return
        val doc = tab.lsp ?: return
        val (line, col) = tab.cursorLine - 1 to tab.cursorCol - 1
        scope.launch(Dispatchers.Main.immediate) {
            val loc = lsp.definition(doc, line, col).firstOrNull()
            if (loc == null) { message = "No definition found"; return@launch }
            val target = loc.range.start.line.toInt() to loc.range.start.character.toInt()
            if (loc.path == tab.path) tab.jumpTo?.invoke(target.first, target.second)
            else open(loc.path, target)
        }
    }

    fun onTyped(tab: EditorTab, typed: String, line: Int, col: Int) {
        val doc = tab.lsp ?: return
        if (typed == ")") { signature = null; return }
        if (typed !in doc.signatureTriggers && signature == null) return
        signatureLine = line
        scope.launch(Dispatchers.Main.immediate) {
            val sig = lsp.signature(doc, line, col)
            if (activeKey == tab.key && signatureLine == line) signature = sig
        }
    }

    fun onCursorMoved(tab: EditorTab, line: Int) {
        if (signature != null && (activeKey != tab.key || line - 1 != signatureLine)) signature = null
    }

    fun closeTab(tab: EditorTab, force: Boolean = false) {
        tab.file?.takeIf { f -> tabs.none { it !== tab && it.file == f } }?.delete()
        if (tab.dirty && !force) {
            message = "${tab.title} has unsaved changes. Save it, or close it again to discard them."
            tab.dirty = false
            return
        }
        val i = tabs.indexOf(tab)
        detachLsp(tab)
        tabs.remove(tab)
        if (activeKey == tab.key) activeKey = tabs.getOrNull(i.coerceAtMost(tabs.size - 1))?.key
    }

    fun save(tab: EditorTab? = activeTab, overwrite: Boolean = false) {
        if (tab == null || tab.readOnly || tab.saving) return
        tab.saving = true
        launchIo("Could not save") {
            val st = remote.stat(tab.path)
            val remoteMtime = st?.mtime?.toLong() ?: 0L
            if (!overwrite && st != null && tab.mtime != 0L && remoteMtime != tab.mtime) {
                withMain { saveConflict = SaveConflict(tab) }
                return@launchIo
            }
            val saved = tab.text()
            remote.write(tab.path, saved.toByteArray(Charsets.UTF_8))
            val after = remote.stat(tab.path)?.mtime?.toLong() ?: 0L
            withMain {
                lsp.saved(tab.path, saved)
                tab.mtime = after
                tab.dirty = false
                message = "Saved ${tab.title}"
            }
            refreshGit()
        }.invokeOnCompletion { tab.saving = false }
    }

    fun reloadFromRemote(tab: EditorTab) {
        detachLsp(tab)
        tabs.remove(tab)
        if (activeKey == tab.key) activeKey = null
        open(tab.path)
    }

    var openingTerminal by mutableStateOf(false)
        private set

    fun newTerminal(cols: Int = 100, rows: Int = 30, then: ((TermTab) -> Unit)? = null) =
        openTerminals(fresh = true, cols, rows, then)

    fun ensureTerminal() {
        if (terminals.isEmpty() && !connectionLost) openTerminals(fresh = false, 100, 30, null)
    }

    private fun openTerminals(fresh: Boolean, cols: Int, rows: Int, then: ((TermTab) -> Unit)?) {
        if (openingTerminal) return
        openingTerminal = true
        launchIo("Could not open a terminal") {
            val restore = probeTmux()
            if (prefs.keepSessions) {
                for (name in restore) {
                    val id = name.removePrefix(TMUX_PREFIX).toIntOrNull() ?: continue
                    if (withMain { terminals.any { it.tmuxSession == name } }) continue
                    val tab = openTab(id, name, cols, rows)
                    withMain { nextTermId = maxOf(nextTermId, id + 1); addTerminal(tab) }
                }
            }
            if (fresh || withMain { terminals.isEmpty() }) {
                val id = withMain { nextTermId++ }
                val session = if (tmuxAvailable && prefs.keepSessions) "$TMUX_PREFIX$id" else null
                val tab = openTab(id, session, cols, rows)
                withMain { addTerminal(tab); then?.invoke(tab) }
            }
        }.invokeOnCompletion { openingTerminal = false }
    }

    private suspend fun probeTmux(): List<String> = probeLock.withLock {
        if (tmuxProbed) return emptyList()
        val info = runCatching { parseTmuxProbe(remote.exec(tmuxProbeCommand(), 30).stdout) }.getOrNull()
        tmuxProbed = true
        withMain { tmuxAvailable = info?.available == true }
        info?.sessions.orEmpty()
    }

    private fun openTab(id: Int, session: String?, cols: Int, rows: Int): TermTab {
        var tab: TermTab? = null
        val holder = object : TerminalListener {
            override fun onUpdate() { tab?.takeIf { it.firstGeneration }?.onUpdate() }
            override fun onExit(message: String?) { tab?.takeIf { it.firstGeneration }?.onExit(message) }
        }
        val t = remote.openTerminal(cols, rows, session?.let { tmuxAttachCommand(it) }, holder)
        configure(t)
        return TermTab(id, t, session).also { it.title = "bash $id"; tab = it }
    }

    private fun addTerminal(tab: TermTab) {
        terminals += tab
        activeTerminal = terminals.size - 1
        terminalVisible = true
    }

    fun attachTerminal(t: Terminal, title: String): TermTab =
        TermTab(nextTermId++, t).also { it.title = title; terminals += it; activeTerminal = terminals.size - 1 }

    fun closeTerminal(tab: TermTab) {
        tab.terminal.terminate()
        terminals.remove(tab)
        activeTerminal = activeTerminal.coerceAtMost(terminals.size - 1).coerceAtLeast(0)
        val session = tab.tmuxSession ?: return
        if (!tab.endedCleanly && !connectionLost) launchIo(null) { remote.exec(tmuxKillCommand(session), 15) }
    }

    private fun withTerminal(action: (TermTab) -> Unit) {
        val t = terminals.getOrNull(activeTerminal)?.takeIf { it.exited == null }
        if (t != null) action(t) else newTerminal(then = action)
    }

    fun typeInTerminal(text: String) {
        terminalVisible = true
        withTerminal { it.paste(text) }
    }

    fun cdInTerminal(dir: String) {
        terminalVisible = true
        withTerminal { it.type("cd ${shQuote(dir)}\r") }
    }

    fun runFile(path: String, slurm: Boolean = false) {
        val tab = tabs.firstOrNull { it.path == path }
        if (tab?.dirty == true) save(tab)
        val kind = Languages.runKind(pathName(path))
        val dir = shQuote(pathParent(path))
        val file = shQuote(pathName(path))
        val cmd = when {
            slurm || kind == Languages.RunKind.Slurm -> "sbatch $file"
            kind == Languages.RunKind.Python -> "${profile.pythonCmd} $file"
            kind == Languages.RunKind.Shell -> "bash $file"
            else -> { message = "This file type cannot be run directly"; return }
        }
        terminalVisible = true
        withTerminal { it.type("cd $dir && $cmd\r") }
    }

    fun refreshGit() {
        launchIo(null) {
            val top = remote.exec(gitCommand(root, GitOp.TopLevel), 30)
            if (!top.ok) {
                withMain { git.repo = null; git.status = null; git.notRepo = true }
                return@launchIo
            }
            val repo = top.stdout.trim()
            val st = remote.exec(gitCommand(repo, GitOp.Status), 60)
            withMain {
                git.repo = repo
                git.notRepo = false
                git.status = if (st.ok) parseGitStatus(st.stdout) else null
                if (!st.ok) git.output = st.combined
            }
        }
    }

    private fun gitRun(op: GitOp, label: String, refresh: Boolean = true) {
        val repo = git.repo ?: root
        git.busy = true
        launchIo("git $label failed") {
            val r = remote.exec(gitCommand(repo, op), 300)
            withMain {
                git.output = r.combined.ifEmpty { if (r.ok) "git $label done" else "git $label failed (exit ${r.exitCode})" }
                if (op is GitOp.Commit && r.ok) git.commitMessage = ""
            }
            if (refresh) refreshGit()
        }.invokeOnCompletion { git.busy = false }
    }

    fun gitStage(paths: List<String>) = gitRun(GitOp.Stage(paths), "add")
    fun gitUnstage(paths: List<String>) = gitRun(GitOp.Unstage(paths), "reset")
    fun gitDiscard(paths: List<String>) = gitRun(GitOp.Discard(paths), "checkout")
    fun gitCommit() {
        val msg = git.commitMessage.trim()
        if (msg.isEmpty()) { message = "Write a commit message first"; return }
        gitRun(GitOp.Commit(msg), "commit")
    }
    fun gitPull() = gitRun(GitOp.Pull, "pull")
    fun gitPush() = gitRun(GitOp.Push, "push")
    fun gitFetch() = gitRun(GitOp.Fetch, "fetch")
    fun gitInit() { git.repo = root; gitRun(GitOp.Init, "init") }

    fun openDiff(change: GitChange, staged: Boolean) {
        val repo = git.repo ?: return
        launchIo("Could not read the diff") {
            val op = if (change.untracked) GitOp.DiffUntracked(change.path) else GitOp.Diff(change.path, staged)
            val r = remote.exec(gitCommand(repo, op), 60)
            val text = r.stdout.ifEmpty { r.combined.ifEmpty { "(no differences)" } }
            withMain { openReadOnly("diff:${if (staged) "s" else "w"}:${change.path}", "${pathName(change.path)}.diff", text) }
        }
    }

    fun shortcut(ctrl: Boolean, shift: Boolean, key: Char): Boolean {
        if (!ctrl) return false
        when {
            key == 's' -> save()
            key == ' ' -> activeTab?.requestCompletion?.invoke()
            key == 'i' -> showHover()
            key == '`' || key == 'j' -> terminalVisible = !terminalVisible
            key == 'b' -> sidebarVisible = !sidebarVisible
            key == 'w' -> activeTab?.let { closeTab(it) }
            key == '\r' -> activeTab?.takeIf { !it.readOnly }?.let { runFile(it.path) }
            shift && key == 'g' -> { sidebarVisible = true; panel = SidePanel.Git }
            shift && key == 'e' -> { sidebarVisible = true; panel = SidePanel.Explorer }
            key == '=' || key == '+' -> editorTextSp = (editorTextSp + 1f).coerceAtMost(28f)
            key == '-' -> editorTextSp = (editorTextSp - 1f).coerceAtLeast(9f)
            else -> return false
        }
        return true
    }

    fun absolute(repoRelative: String): String = pathJoin(git.repo ?: root, repoRelative)

    private fun launchIo(errorPrefix: String?, block: suspend CoroutineScope.() -> Unit) = scope.launch(io) {
        try {
            block()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val lost = e is CoreException.Closed || !runCatching { remote.isConnected }.getOrDefault(false)
            withMain {
                if (lost) markLost()
                if (errorPrefix != null && !lost) message = "$errorPrefix: ${describe(e)}"
            }
        }
    }

    private suspend fun <T> withMain(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    companion object {
        const val MAX_MEDIA_BYTES = 40L * 1024 * 1024
        const val TMUX_PREFIX = "codetab-"
        const val PING_TIMEOUT_MS = 4000
        const val PING_MAX_FAILURES = 2

        fun describe(e: Throwable): String = when (e) {
            is CoreException -> e.message ?: e.javaClass.simpleName
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
