package app.jangkau

import app.jangkau.core.CoreException
import app.jangkau.core.ExecResult
import app.jangkau.model.HostProfile
import app.jangkau.model.ProfileStore
import app.jangkau.remote.Remote
import app.jangkau.security.AesGcmBox
import app.jangkau.security.KeyVault
import app.jangkau.ui.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

@OptIn(ExperimentalCoroutinesApi::class)
class AppStateTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val remotes = mutableListOf<FakeRemote>()
    private var attempts = 0
    private var failure: (() -> Exception)? = null
    private lateinit var profile: HostProfile

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun state(scope: CoroutineScope): AppState {
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val vault = KeyVault(tmp.newFolder("keys"), AesGcmBox { aes })
        val key = vault.generateEd25519("tablet")
        val store = ProfileStore(File(tmp.root, "profiles.json"))
        profile = HostProfile(name = "Lab", host = "dev.example.com", user = "user", keyId = key.id)
        store.upsert(profile)
        return AppState(
            store, vault, File(tmp.root, "known_hosts").path, scope,
            connector = { _, _ ->
                attempts++
                failure?.let { throw it() }
                FakeRemote().also { r ->
                    r.execHandler = { cmd -> if ("list-sessions" in cmd) ExecResult(0, "__codetab_tmux__\n", "") else ExecResult(0, "", "") }
                    remotes += r
                } as Remote
            },
            io = dispatcher,
            retryDelaysMs = listOf(0, 1_000, 2_000),
            pingIntervalMs = 5_000,
        )
    }

    @Test fun pingLossTriggersReconnectWithBackoff() = scope.runTest {
        val st = state(backgroundScope)
        st.connect(profile); runCurrent()
        val ws = st.workspace!!
        assertEquals(1, attempts)

        failure = { CoreException.Connect("Connection to dev.example.com refused") }
        remotes[0].pingMs = null
        advanceTimeBy(10_001); runCurrent()
        assertTrue(ws.connectionLost)
        assertEquals(2, attempts)
        assertTrue(ws.reconnectStatus!!.startsWith("Reconnecting in 1 s"))

        failure = null
        advanceTimeBy(1_001); runCurrent()
        assertEquals(3, attempts)
        assertFalse(ws.connectionLost)
        assertSame(remotes[1], ws.remote)
        assertNull(ws.reconnectStatus)
        assertSame(ws, st.workspace)
    }

    @Test fun authFailureStopsRetrying() = scope.runTest {
        val st = state(backgroundScope)
        st.connect(profile); runCurrent()
        val ws = st.workspace!!
        failure = { CoreException.Auth("Authentication for user@dev.example.com was rejected.") }
        ws.markLost(); runCurrent()
        assertEquals(2, attempts)
        assertNotNull(st.error)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, attempts)
        assertTrue(ws.connectionLost)
    }

    @Test fun networkChangesReconnectAndWaitForConnectivity() = scope.runTest {
        val st = state(backgroundScope)
        st.onNetworkChanged(true, 1L)
        st.connect(profile); runCurrent()
        val ws = st.workspace!!

        st.onNetworkChanged(true, 2L); runCurrent()
        assertEquals(2, attempts)
        assertFalse(ws.connectionLost)
        assertSame(remotes[1], ws.remote)

        st.onNetworkChanged(false, null)
        ws.markLost(); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, attempts)
        assertEquals("Waiting for the network…", ws.reconnectStatus)

        st.onNetworkChanged(true, 3L); runCurrent()
        assertEquals(3, attempts)
        assertFalse(ws.connectionLost)
    }
}
