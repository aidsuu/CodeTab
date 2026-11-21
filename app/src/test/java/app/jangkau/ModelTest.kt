package app.jangkau

import app.jangkau.editor.Languages
import app.jangkau.model.Hop
import app.jangkau.model.HostProfile
import app.jangkau.model.ProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val base = HostProfile(name = "Server", host = "server.example.com", user = "user", keyId = "k1")

    @Test fun validation() {
        assertNull(base.validate())
        assertEquals("Pilih kunci SSH", base.copy(keyId = null).validate())
        assertEquals("Host tidak valid", base.copy(host = "server example").validate())
        assertEquals("Port harus 1–65535", base.copy(port = 0).validate())
        assertEquals("Jump host belum lengkap", base.copy(jump = Hop("", 22, "user")).validate())
        assertNull(base.copy(jump = Hop("bastion.example.com", 2222, "user")).validate())
    }

    @Test fun summaryShowsJump() {
        assertEquals("user@server.example.com", base.summary)
        assertEquals("user@server.example.com:2200  via gw@bastion", base.copy(port = 2200, jump = Hop("bastion", 22, "gw")).summary)
    }

    @Test fun storeRoundTripWithoutSecrets() {
        val f = tmp.newFile("profiles.json")
        val store = ProfileStore(f)
        assertTrue(store.load().isEmpty())
        val p = base.copy(jump = Hop("bastion", 22, "user", keyId = "k2"), pythonCmd = "module load python/3.11 && python")
        store.upsert(p)
        store.upsert(p.copy(name = "Server baru"))
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals("Server baru", loaded[0].name)
        assertEquals(p.jump, loaded[0].jump)
        assertEquals(p.pythonCmd, loaded[0].pythonCmd)
        assertTrue(store.remove(p.id).isEmpty())
        assertTrue("PRIVATE" !in f.readText())
    }

    @Test fun corruptStoreLoadsEmpty() {
        val f = tmp.newFile("bad.json").apply { writeText("{not json") }
        assertTrue(ProfileStore(f).load().isEmpty())
    }

    @Test fun languagesAndRunKinds() {
        assertEquals("source.python", Languages.forName("train.py").scope)
        assertEquals("source.shell", Languages.forName("job.SLURM").scope)
        assertEquals("source.shell", Languages.forName(".bashrc").scope)
        assertEquals("source.yaml", Languages.forName("env.yml").scope)
        assertEquals("source.diff", Languages.forName("a.py.diff").scope)
        assertNull(Languages.forName("Makefile").scope)
        assertEquals(Languages.RunKind.Python, Languages.runKind("a.py"))
        assertEquals(Languages.RunKind.Slurm, Languages.runKind("train.sbatch"))
        assertNull(Languages.runKind("README.md"))
    }
}
