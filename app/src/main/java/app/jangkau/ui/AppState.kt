package app.jangkau.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.jangkau.core.ConnectConfig
import app.jangkau.core.ConnectListener
import app.jangkau.core.CoreException
import app.jangkau.core.Hop
import app.jangkau.core.Session
import app.jangkau.model.HostProfile
import app.jangkau.model.ProfileStore
import app.jangkau.remote.Remote
import app.jangkau.remote.RustRemote
import app.jangkau.security.KeyVault
import app.jangkau.security.StoredKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class HostKeyPrompt(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val previousFingerprint: String?,
    val answer: CompletableDeferred<Boolean>,
)

data class PassphraseRequest(val profile: HostProfile, val keys: List<StoredKey>)

class AppState(
    private val profilesStore: ProfileStore,
    private val vault: KeyVault,
    private val knownHostsFile: String,
    private val scope: CoroutineScope,
    val prefs: UiPrefs = UiPrefs(),
    private val cacheDir: java.io.File = java.io.File(System.getProperty("java.io.tmpdir"), "codetab-preview"),
    private val connector: (ConnectConfig, ConnectListener) -> Remote = { cfg, l -> RustRemote(Session.connect(cfg, l)) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val retryDelaysMs: List<Long> = listOf(0, 2_000, 4_000, 8_000, 15_000, 30_000),
    private val pingIntervalMs: Long = 5_000,
) {
    var profiles by mutableStateOf(profilesStore.load()); private set
    var keys by mutableStateOf(vault.list()); private set
    var progress by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null)
    var hostKeyPrompt by mutableStateOf<HostKeyPrompt?>(null); private set
    var passphraseRequest by mutableStateOf<PassphraseRequest?>(null); private set
    var workspace by mutableStateOf<WorkspaceState?>(null); private set

    var networkAvailable = true
        private set
    private var lastNetwork: Long? = null
    private var reconnectJob: Job? = null
    private var monitorJob: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val passphrases = HashMap<String, String>()

    fun saveProfile(p: HostProfile) { profiles = profilesStore.upsert(p) }
    fun deleteProfile(id: String) { profiles = profilesStore.remove(id) }

    fun generateKey(name: String): StoredKey = vault.generateEd25519(name).also { keys = vault.list() }
    fun importKey(name: String, text: String): StoredKey = vault.import(name, text).also { keys = vault.list() }
    fun deleteKey(id: String) {
        vault.delete(id)
        passphrases.remove(id)
        keys = vault.list()
    }

    private fun keysNeeded(p: HostProfile): List<StoredKey> =
        listOfNotNull(vault.find(p.keyId), p.jump?.let { vault.find(it.keyId ?: p.keyId) }).distinctBy { it.id }

    fun connect(p: HostProfile) {
        p.validate()?.let { error = it; return }
        val missing = keysNeeded(p).filter { it.hasPassphrase && it.id !in passphrases }
        if (missing.isNotEmpty()) passphraseRequest = PassphraseRequest(p, missing)
        else start(p)
    }

    fun submitPassphrases(values: Map<String, String>?) {
        val req = passphraseRequest ?: return
        passphraseRequest = null
        if (values == null) return
        passphrases.putAll(values)
        start(req.profile)
    }

    fun answerHostKey(trust: Boolean) {
        hostKeyPrompt?.answer?.complete(trust)
        hostKeyPrompt = null
    }

    fun reconnect() {
        val ws = workspace ?: return
        ws.markLost()
        if (reconnectJob?.isActive == true) wake.trySend(Unit) else autoReconnect(ws)
    }

    fun disconnect() {
        val ws = workspace ?: return
        workspace = null
        reconnectJob?.cancel()
        monitorJob?.cancel()
        ws.onConnectionLost = null
        scope.launch(io) { runCatching { ws.remote.close() } }
    }

    fun onNetworkChanged(available: Boolean, networkId: Long?) {
        networkAvailable = available
        val previous = lastNetwork
        if (available && networkId != null) lastNetwork = networkId
        val ws = workspace ?: return
        if (available && previous != null && networkId != null && networkId != previous) ws.markLost()
        if (available) wake.trySend(Unit)
    }

    private fun listener(onProgress: (String) -> Unit) = object : ConnectListener {
        override fun progress(step: String) { scope.launch(Dispatchers.Main) { onProgress(step) } }

        override fun confirmHostKey(host: String, port: UShort, fingerprint: String, previousFingerprint: String?): Boolean {
            val d = CompletableDeferred<Boolean>()
            scope.launch(Dispatchers.Main) { hostKeyPrompt = HostKeyPrompt(host, port.toInt(), fingerprint, previousFingerprint, d) }
            return runBlocking { d.await() }
        }
    }

    private fun start(p: HostProfile) {
        if (progress != null) return
        progress = "Preparing connection…"
        val l = listener { step -> if (progress != null) progress = step }
        scope.launch(io) {
            try {
                val remote = connector(buildConfig(p), l)
                withContext(Dispatchers.Main) {
                    progress = null
                    open(WorkspaceState(remote, p, scope, io, prefs = prefs, cacheDir = cacheDir))
                }
            } catch (e: Exception) {
                if (e.message?.contains("assphrase") == true) keysNeeded(p).forEach { passphrases.remove(it.id) }
                withContext(Dispatchers.Main) {
                    progress = null
                    error = WorkspaceState.describe(e)
                }
            }
        }
    }

    private fun open(ws: WorkspaceState) {
        workspace = ws
        ws.onConnectionLost = { autoReconnect(ws) }
        ws.start()
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive && workspace === ws) {
                delay(pingIntervalMs)
                ws.pingOnce()
            }
        }
    }

    private fun autoReconnect(ws: WorkspaceState) {
        if (reconnectJob?.isActive == true || workspace !== ws) return
        reconnectJob = scope.launch(Dispatchers.Main.immediate) {
            var attempt = 0
            while (isActive && workspace === ws && ws.connectionLost) {
                val wait = retryDelaysMs[attempt.coerceAtMost(retryDelaysMs.size - 1)]
                if (wait > 0) {
                    ws.reconnectStatus = "Reconnecting in ${(wait + 999) / 1000} s · tap to retry now"
                    withTimeoutOrNull(wait) { wake.receive() }
                }
                while (!networkAvailable && isActive) {
                    ws.reconnectStatus = "Waiting for the network…"
                    wake.receive()
                }
                attempt++
                ws.reconnectStatus = "Reconnecting…"
                val result = runCatching {
                    withContext(io) { connector(buildConfig(ws.profile), listener { step -> ws.reconnectStatus = step }) }
                }
                if (workspace !== ws) {
                    result.getOrNull()?.let { r -> launch(io) { runCatching { r.close() } } }
                    return@launch
                }
                val remote = result.getOrNull()
                if (remote != null) {
                    ws.replaceRemote(remote)
                    return@launch
                }
                val e = result.exceptionOrNull()
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is CoreException.Auth || e is CoreException.HostKeyRejected || e is CoreException.Key || e is IllegalStateException) {
                    ws.reconnectStatus = null
                    error = WorkspaceState.describe(e)
                    return@launch
                }
            }
            ws.reconnectStatus = null
        }
    }

    private fun buildConfig(p: HostProfile): ConnectConfig {
        fun hop(host: String, port: Int, user: String, keyId: String?): Hop {
            val key = vault.find(keyId) ?: throw IllegalStateException("SSH key for $user@$host not found")
            return Hop(host.trim(), port.toUShort(), user.trim(), vault.privateKeyText(key.id), passphrases[key.id])
        }
        return ConnectConfig(
            target = hop(p.host, p.port, p.user, p.keyId),
            jump = p.jump?.let { hop(it.host, it.port, it.user, it.keyId ?: p.keyId) },
            knownHostsFile = knownHostsFile,
            timeoutSecs = 20u,
        )
    }
}
