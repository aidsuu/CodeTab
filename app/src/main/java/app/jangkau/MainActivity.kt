package app.jangkau

import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import app.jangkau.ui.AppState
import app.jangkau.ui.ConnectScreen
import app.jangkau.ui.ConnectionOverlays
import app.jangkau.ui.Ide
import app.jangkau.ui.JangkauTheme
import app.jangkau.ui.UiPrefs
import app.jangkau.ui.WorkspaceScreen

class MainActivity : ComponentActivity() {
    private lateinit var state: AppState
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { state.onNetworkChanged(true, network.networkHandle) }
        }

        override fun onLost(network: Network) {
            runOnUiThread {
                val active = connectivity.activeNetwork
                state.onNetworkChanged(active != null, active?.networkHandle)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as JangkauApp
        state = AppState(
            app.profiles, app.vault, app.knownHostsFile, lifecycleScope,
            prefs = UiPrefs(getSharedPreferences("ui", MODE_PRIVATE)),
            cacheDir = java.io.File(cacheDir, "preview").also { d -> d.deleteRecursively() },
        )
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
        setContent {
            JangkauTheme {
                Box(Modifier.fillMaxSize().background(Ide.editor).safeDrawingPadding()) {
                    val ws = state.workspace
                    if (ws == null) {
                        ConnectScreen(
                            profiles = state.profiles,
                            keys = state.keys,
                            onConnect = state::connect,
                            onSaveProfile = state::saveProfile,
                            onDeleteProfile = state::deleteProfile,
                            onGenerateKey = state::generateKey,
                            onImportKey = state::importKey,
                            onDeleteKey = state::deleteKey,
                        )
                    } else {
                        BackHandler { ws.sidebarVisible = !ws.sidebarVisible }
                        WorkspaceScreen(ws, onDisconnect = state::disconnect, onReconnect = state::reconnect)
                    }
                    ConnectionOverlays(state)
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val ws = state.workspace
        if (ws != null && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_F12 &&
            currentFocus !is app.jangkau.term.TerminalView) {
            ws.goToDefinition()
            return true
        }
        if (ws != null && event.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed) {
            val ch = when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER -> '\r'
                KeyEvent.KEYCODE_GRAVE -> '`'
                KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS -> '='
                KeyEvent.KEYCODE_MINUS -> '-'
                else -> event.getUnicodeChar(0).takeIf { it > 0 }?.toChar()?.lowercaseChar()
            }
            val inTerminal = currentFocus is app.jangkau.term.TerminalView
            val allowed = if (inTerminal) ch == '`' || ch == 'j' || (ch == 's' && event.isShiftPressed) else true
            if (ch != null && allowed && ws.shortcut(ctrl = true, shift = event.isShiftPressed, key = ch)) return true
        }
        return super.dispatchKeyEvent(event)
    }
}
