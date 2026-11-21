package app.jangkau

import android.app.Application
import app.jangkau.model.ProfileStore
import app.jangkau.security.KeyVault
import app.jangkau.security.KeystoreBox
import java.io.File

class JangkauApp : Application() {
    val profiles by lazy { ProfileStore(File(filesDir, "profiles.json")) }
    val vault by lazy { KeyVault(File(noBackupFilesDir, "keys"), KeystoreBox()) }
    val knownHostsFile: String get() = File(noBackupFilesDir, "known_hosts").absolutePath
}
