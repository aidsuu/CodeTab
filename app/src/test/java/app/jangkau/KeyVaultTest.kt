package app.jangkau

import app.jangkau.security.AesGcmBox
import app.jangkau.security.KeyImportException
import app.jangkau.security.KeyVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class KeyVaultTest {
    @get:Rule val tmp = TemporaryFolder()
    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun vault() = KeyVault(tmp.root, AesGcmBox { aes }, clock = { 42L })

    @Test fun generatedKeyIsEncryptedAtRest() {
        val v = vault()
        val k = v.generateEd25519("office tablet")
        assertEquals("ed25519", k.format)
        assertTrue(k.publicLine!!.startsWith("ssh-ed25519 AAAA"))
        assertTrue(k.publicLine!!.endsWith("office-tablet@codetab"))
        assertTrue(k.fingerprint!!.startsWith("SHA256:"))
        assertFalse(k.hasPassphrase)

        val pem = v.privateKeyText(k.id)
        assertTrue(pem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        tmp.root.walk().filter { it.isFile }.forEach { f ->
            assertFalse("${f.name} leaks key material", f.readBytes().toString(Charsets.ISO_8859_1).contains("PRIVATE KEY"))
        }
        assertEquals(listOf(k), vault().list())
    }

    @Test fun importKeepsMetadataAndRejectsGarbage() {
        val v = vault()
        val src = v.generateEd25519("src")
        val imported = v.import("from laptop", v.privateKeyText(src.id))
        assertEquals("OpenSSH", imported.format)
        assertEquals(src.fingerprint, imported.fingerprint)
        assertNotNull(imported.publicLine)
        assertTrue(runCatching { v.import("pub", src.publicLine!!) }.exceptionOrNull() is KeyImportException)
        assertEquals(2, v.list().size)
        v.delete(src.id)
        assertEquals(listOf(imported.id), v.list().map { it.id })
    }

    @Test fun tamperedCiphertextFails() {
        val v = vault()
        val k = v.generateEd25519("x")
        val f = tmp.root.resolve("${k.id}.key")
        val bytes = f.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        f.writeBytes(bytes)
        assertTrue(runCatching { v.privateKeyText(k.id) }.isFailure)
    }
}
