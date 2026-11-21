package app.jangkau.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.jangkau.core.inspectPrivateKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** AES-256-GCM, stored as `[iv length][iv][ciphertext + tag]`. */
open class AesGcmBox(private val key: () -> SecretKey) : SecretBox {
    override fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val n = sealed[0].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 1, n))
        return c.doFinal(sealed, 1 + n, sealed.size - 1 - n)
    }
}

class KeystoreBox(private val alias: String = "jangkau-vault") : AesGcmBox({ keystoreKey(alias) }) {
    companion object {
        private fun keystoreKey(alias: String): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(alias, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return gen.generateKey()
        }
    }
}

@Serializable
data class StoredKey(
    val id: String,
    val name: String,
    val format: String,
    val publicLine: String? = null,
    val fingerprint: String? = null,
    val hasPassphrase: Boolean = false,
    val createdAt: Long = 0,
)

class KeyImportException(msg: String) : Exception(msg)

class KeyVault(private val dir: File, private val box: SecretBox, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val index get() = File(dir, "keys.json")

    @Synchronized
    fun list(): List<StoredKey> =
        if (!index.exists()) emptyList()
        else runCatching { json.decodeFromString<List<StoredKey>>(index.readText()) }.getOrDefault(emptyList())

    fun find(id: String?): StoredKey? = id?.let { k -> list().firstOrNull { it.id == k } }

    fun generateEd25519(name: String): StoredKey {
        val g = app.jangkau.core.generateEd25519(name.trim().replace(' ', '-') + "@jangkau")
        return store(name, g.privatePem, StoredKey(newId(), name, "ed25519", g.publicLine, g.fingerprint, false, clock()))
    }

    fun import(name: String, text: String): StoredKey {
        val info = inspectPrivateKey(text)
            ?: throw KeyImportException("Format kunci tidak dikenali. Tempel isi file private key (mis. id_ed25519), bukan file .pub.")
        val meta = StoredKey(
            id = newId(),
            name = name,
            format = info.format,
            publicLine = info.publicLine,
            fingerprint = info.fingerprint,
            hasPassphrase = info.encrypted,
            createdAt = clock(),
        )
        return store(name, text.trim() + "\n", meta)
    }

    fun privateKeyText(id: String): String =
        String(box.open(File(dir, "$id.key").readBytes()), Charsets.UTF_8)

    @Synchronized
    fun delete(id: String) {
        File(dir, "$id.key").delete()
        writeIndex(list().filterNot { it.id == id })
    }

    @Synchronized
    private fun store(name: String, secret: String, meta: StoredKey): StoredKey {
        require(name.isNotBlank()) { "Nama kunci kosong" }
        dir.mkdirs()
        File(dir, "${meta.id}.key").writeBytes(box.seal(secret.toByteArray(Charsets.UTF_8)))
        writeIndex(list() + meta)
        return meta
    }

    private fun writeIndex(keys: List<StoredKey>) {
        dir.mkdirs()
        index.writeText(json.encodeToString(keys))
    }

    private fun newId() = UUID.randomUUID().toString().take(12)
}
