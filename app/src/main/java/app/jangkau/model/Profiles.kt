package app.jangkau.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class Hop(
    val host: String,
    val port: Int = 22,
    val user: String,
    val keyId: String? = null,
)

@Serializable
data class HostProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val keyId: String? = null,
    val jump: Hop? = null,
    val workDir: String = "~",
    val pythonCmd: String = "python3",
) {
    val summary: String
        get() = buildString {
            append(user).append('@').append(host)
            if (port != 22) append(':').append(port)
            jump?.let { append("  via ").append(it.user).append('@').append(it.host) }
        }

    fun validate(): String? = when {
        name.isBlank() -> "Nama profil kosong"
        host.isBlank() || host.any { it.isWhitespace() } -> "Host tidak valid"
        user.isBlank() || user.any { it.isWhitespace() } -> "User tidak valid"
        port !in 1..65535 -> "Port harus 1–65535"
        keyId == null -> "Pilih kunci SSH"
        jump != null && (jump.host.isBlank() || jump.user.isBlank()) -> "Jump host belum lengkap"
        jump != null && jump.port !in 1..65535 -> "Port jump host harus 1–65535"
        else -> null
    }
}

class ProfileStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    @Synchronized
    fun load(): List<HostProfile> =
        if (!file.exists()) emptyList()
        else runCatching { json.decodeFromString<List<HostProfile>>(file.readText()) }.getOrDefault(emptyList())

    @Synchronized
    fun save(profiles: List<HostProfile>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(profiles))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun upsert(p: HostProfile): List<HostProfile> {
        val list = load().toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list += p
        save(list)
        return list
    }

    fun remove(id: String): List<HostProfile> = load().filterNot { it.id == id }.also(::save)
}
