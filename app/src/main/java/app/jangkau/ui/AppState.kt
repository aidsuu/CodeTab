package app.jangkau.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.jangkau.core.ConnectConfig
import app.jangkau.core.ConnectListener
import app.jangkau.core.Hop
import app.jangkau.core.Session
import app.jangkau.model.HostProfile
import app.jangkau.model.ProfileStore
import app.jangkau.remote.Remote
import app.jangkau.remote.RustRemote
import app.jangkau.security.KeyVault
import app.jangkau.security.StoredKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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
) {
    var profiles by mutableStateOf(profilesStore.load()); private set
    var keys by mutableStateOf(vault.list()); private set
    var progress by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null)
    var hostKeyPrompt by mutableStateOf<HostKeyPrompt?>(null); private set
    var passphraseRequest by mutableStateOf<PassphraseRequest?>(null); private set
    var workspace by mutableStateOf<WorkspaceState?>(null); private set

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
        start(ws.profile, existing = ws)
    }

    fun disconnect() {
        val ws = workspace ?: return
        workspace = null
        scope.launch(Dispatchers.IO) { runCatching { ws.remote.close() } }
    }

    private fun start(p: HostProfile, existing: WorkspaceState? = null) {
        if (progress != null) return
        progress = "Preparing connection…"
        val listener = object : ConnectListener {
            override fun progress(step: String) { scope.launch(Dispatchers.Main) { if (progress != null) progress = step } }

            override fun confirmHostKey(host: String, port: UShort, fingerprint: String, previousFingerprint: String?): Boolean {
                val d = CompletableDeferred<Boolean>()
                scope.launch(Dispatchers.Main) { hostKeyPrompt = HostKeyPrompt(host, port.toInt(), fingerprint, previousFingerprint, d) }
                return runBlocking { d.await() }
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                val cfg = buildConfig(p)
                val remote = connector(cfg, listener)
                withContext(Dispatchers.Main) {
                    progress = null
                    if (existing != null && workspace === existing) existing.replaceRemote(remote)
                    else workspace = WorkspaceState(remote, p, scope, prefs = prefs, cacheDir = cacheDir).also { it.start() }
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

    private fun buildConfig(p: HostProfile): ConnectConfig {
        fun hop(host: String, port: Int, user: String, keyId: String?): Hop {
            val key = vault.find(keyId) ?: throw IllegalStateException("SSH key for $user@$host not found")
            return Hop(host.trim(), port.toUShort(), user.trim(), vault.privateKeyText(key.id), passphrases[key.id])
        }
        return ConnectConfig(
            target = hop(p.host, p.port, p.user, p.keyId),
            jump = p.jump?.let { hop(it.host, it.port, it.user, it.keyId ?: p.keyId) },
            knownHostsFile = knownHostsFile,
            timeoutSecs = 25u,
        )
    }
}
