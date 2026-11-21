package app.jangkau.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import app.jangkau.model.HostProfile
import app.jangkau.model.Hop
import app.jangkau.security.StoredKey

@Composable
fun ConnectScreen(
    profiles: List<HostProfile>,
    keys: List<StoredKey>,
    onConnect: (HostProfile) -> Unit,
    onSaveProfile: (HostProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onGenerateKey: (String) -> StoredKey,
    onImportKey: (String, String) -> StoredKey,
    onDeleteKey: (String) -> Unit,
) {
    var editing by remember { mutableStateOf<HostProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var keyDialog by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf<StoredKey?>(null) }
    var deleteProfile by remember { mutableStateOf<HostProfile?>(null) }
    var deleteKey by remember { mutableStateOf<StoredKey?>(null) }

    Row(Modifier.fillMaxSize().background(Ide.bg).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1.25f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 44.dp, end = 28.dp, top = 40.dp, bottom = 32.dp)) {
            Row(Modifier.padding(bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Ide.textBright, Ide.textDim, Color(0xFF555555)).forEach { c -> Box(Modifier.size(9.dp).clip(Ide.pillShape).background(c)) }
            }
            Text("CodeTab", style = Ide.hero)
            Spacer(Modifier.height(4.dp))
            Text("Editor, terminal, dan git yang seluruhnya berjalan di server remote lewat SSH. Tablet hanya layar dan keyboard.",
                style = Ide.label.copy(color = Ide.textDim, fontSize = 14.sp))
            Spacer(Modifier.height(32.dp))
            Text("Server remote", style = Ide.title.copy(fontSize = 24.sp), modifier = Modifier.padding(bottom = 14.dp))
            if (profiles.isEmpty()) {
                EmptyCard("Belum ada server. Tambahkan server remote Anda (boleh lewat jump host).")
            }
            profiles.forEach { p ->
                ProfileCard(p, keys, onConnect = { onConnect(p) }, onEdit = { editing = p }, onDelete = { deleteProfile = p })
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(6.dp))
            FlatButton("+  Tambah server", primary = false) { creating = true }
            Spacer(Modifier.height(36.dp))
            Text("Langkah pertama", style = Ide.title, modifier = Modifier.padding(bottom = 10.dp))
            listOf(
                "Buat kunci Ed25519 di panel kanan (atau impor kunci yang sudah ada).",
                "Salin public key, lalu tambahkan ke ~/.ssh/authorized_keys di server (dan di jump host bila ada).",
                "Tambah server: isi alamat host, centang \"Lewat jump host\" dan isi bastion bila perlu.",
                "Sambungkan. Sidik jari host akan ditanya sekali, lalu diingat.",
            ).forEachIndexed { i, s ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text("${i + 1}.", style = Ide.label.copy(color = Ide.textBright, fontWeight = FontWeight.SemiBold), modifier = Modifier.width(22.dp))
                    Text(s, style = Ide.label.copy(color = Ide.textDim))
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().clip(Ide.cardShape).background(Ide.card).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 32.dp)) {
            Text("Kunci SSH", style = Ide.title.copy(fontSize = 24.sp))
            Spacer(Modifier.height(6.dp))
            Text("Private key dienkripsi dengan Android Keystore dan tidak pernah keluar dari tablet kecuali untuk autentikasi.",
                style = Ide.small.copy(color = Ide.textDim))
            Spacer(Modifier.height(16.dp))
            if (keys.isEmpty()) EmptyCard("Belum ada kunci.")
            keys.forEach { k ->
                KeyCard(k, onShow = { showKey = k }, onDelete = { deleteKey = k })
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(6.dp))
            FlatButton("+  Buat atau impor kunci") { keyDialog = true }
        }
    }

    if (creating || editing != null) {
        ProfileDialog(
            initial = editing, keys = keys,
            onDismiss = { creating = false; editing = null },
            onSave = { onSaveProfile(it); creating = false; editing = null },
        )
    }
    if (keyDialog) {
        KeyDialog(
            onDismiss = { keyDialog = false },
            onGenerate = { name -> onGenerateKey(name).also { keyDialog = false; showKey = it } },
            onImport = { name, text -> onImportKey(name, text).also { keyDialog = false; showKey = it } },
        )
    }
    showKey?.let { k -> PublicKeyDialog(k) { showKey = null } }
    deleteProfile?.let { p ->
        ConfirmDialog("Hapus host ${p.name}?", "Profil dihapus dari tablet. Tidak ada yang berubah di server.", "Hapus",
            onConfirm = { onDeleteProfile(p.id) }, onDismiss = { deleteProfile = null })
    }
    deleteKey?.let { k ->
        ConfirmDialog("Hapus kunci ${k.name}?", "Private key dihapus permanen dari tablet. Host yang memakai kunci ini tidak bisa tersambung sampai diberi kunci lain.", "Hapus",
            onConfirm = { onDeleteKey(k.id) }, onDismiss = { deleteKey = null })
    }
}

@Composable
private fun SectionTitle(s: String) {
    Text(s, style = Ide.section.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun EmptyCard(s: String) {
    Text(s, style = Ide.label.copy(color = Ide.textDim),
        modifier = Modifier.fillMaxWidth().clip(Ide.cardShape).background(Ide.card).padding(20.dp))
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun ProfileCard(p: HostProfile, keys: List<StoredKey>, onConnect: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val key = keys.firstOrNull { it.id == p.keyId }
    Row(
        Modifier.fillMaxWidth().clip(Ide.cardShape).background(Ide.card).clickable(onClick = onConnect).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Ide.selection), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Dns, null, tint = Ide.textBright, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(p.name, style = Ide.label.copy(fontSize = 16.sp, color = Ide.textBright, fontWeight = FontWeight.SemiBold))
            Text(p.summary, style = Ide.small.copy(color = Ide.textDim, fontFamily = Ide.mono), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "kunci: ${key?.name ?: "(tidak ada)"}  ·  folder: ${p.workDir}  ·  python: ${p.pythonCmd}",
                style = Ide.small.copy(color = if (key == null) Ide.deleted else Ide.textDim, fontSize = 11.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onEdit), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Edit, "Ubah", tint = Ide.textDim, modifier = Modifier.size(18.dp))
        }
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onDelete), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Delete, "Hapus", tint = Ide.textDim, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.clip(Ide.pillShape).background(Ide.pill).clickable(onClick = onConnect).padding(horizontal = 20.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.PlayArrow, null, tint = Ide.onPill, modifier = Modifier.size(17.dp))
            Text(" Sambungkan", style = Ide.label.copy(color = Ide.onPill, fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
        }
    }
}

@Composable
private fun KeyCard(k: StoredKey, onShow: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ide.cardRaised).clickable(onClick = onShow).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (k.hasPassphrase) Icons.Outlined.Lock else Icons.Outlined.Key, null, tint = Ide.modified, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(k.name, style = Ide.label.copy(color = Ide.textBright))
            Text(
                "${k.format} ${if (k.format == "ed25519") "" else "· "}${if (k.hasPassphrase) "berpassphrase · " else ""}${k.fingerprint ?: "sidik jari muncul setelah dibuka"}",
                style = Ide.small.copy(color = Ide.textDim, fontFamily = Ide.mono, fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onShow), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.ContentCopy, "Public key", tint = Ide.textDim, modifier = Modifier.size(17.dp))
        }
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onDelete), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Delete, "Hapus", tint = Ide.textDim, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = Ide.deleted) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, number: Boolean = false, placeholder: String = "") {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier, shape = Ide.itemShape,
        placeholder = if (placeholder.isNotEmpty()) ({ Text(placeholder, color = Ide.textDim) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Ascii, autoCorrectEnabled = false),
    )
}

@Composable
private fun KeyPicker(label: String, keys: List<StoredKey>, selected: String?, allowSame: Boolean, onPick: (String?) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val name = when {
        selected == null && allowSame -> "Sama dengan host tujuan"
        selected == null -> "Pilih kunci…"
        else -> keys.firstOrNull { it.id == selected }?.name ?: "(kunci terhapus)"
    }
    Box(modifier) {
        OutlinedTextField(name, {}, label = { Text(label) }, readOnly = true, singleLine = true, modifier = Modifier.fillMaxWidth())
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(open, { open = false }) {
            if (allowSame) DropdownMenuItem(text = { Text("Sama dengan host tujuan") }, onClick = { open = false; onPick(null) })
            keys.forEach { k -> DropdownMenuItem(text = { Text(k.name) }, onClick = { open = false; onPick(k.id) }) }
            if (keys.isEmpty()) DropdownMenuItem(text = { Text("Belum ada kunci, buat dulu di panel kanan") }, onClick = { open = false })
        }
    }
}

@Composable
private fun ProfileDialog(initial: HostProfile?, keys: List<StoredKey>, onDismiss: () -> Unit, onSave: (HostProfile) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(initial?.user ?: "") }
    var keyId by remember { mutableStateOf(initial?.keyId ?: keys.firstOrNull()?.id) }
    var useJump by remember { mutableStateOf(initial?.jump != null) }
    var jHost by remember { mutableStateOf(initial?.jump?.host ?: "") }
    var jPort by remember { mutableStateOf((initial?.jump?.port ?: 22).toString()) }
    var jUser by remember { mutableStateOf(initial?.jump?.user ?: "") }
    var jKey by remember { mutableStateOf(initial?.jump?.keyId) }
    var workDir by remember { mutableStateOf(initial?.workDir ?: "~") }
    var python by remember { mutableStateOf(initial?.pythonCmd ?: "python3") }
    var err by remember { mutableStateOf<String?>(null) }

    fun build(): HostProfile {
        val base = initial ?: HostProfile(name = "", host = "", user = "")
        return base.copy(
            name = name.trim(), host = host.trim(), port = port.toIntOrNull() ?: 0, user = user.trim(), keyId = keyId,
            jump = if (useJump) Hop(jHost.trim(), jPort.toIntOrNull() ?: 0, jUser.trim().ifEmpty { user.trim() }, jKey) else null,
            workDir = workDir.trim().ifEmpty { "~" }, pythonCmd = python.trim().ifEmpty { "python3" },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.width(620.dp),
        title = { Text(if (initial == null) "Tambah server" else "Ubah ${initial.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Nama", name, { name = it }, Modifier.fillMaxWidth(), placeholder = "mis. Server Lab")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Host", host, { host = it }, Modifier.weight(1f), placeholder = "server.example.com")
                    Field("Port", port, { port = it.filter(Char::isDigit) }, Modifier.width(96.dp), number = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("User", user, { user = it }, Modifier.weight(1f))
                    KeyPicker("Kunci SSH", keys, keyId, allowSame = false, onPick = { keyId = it }, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { useJump = !useJump }) {
                    Checkbox(useJump, { useJump = it })
                    Text("Lewat jump host (bastion / gateway)", style = Ide.label)
                }
                if (useJump) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("Jump host", jHost, { jHost = it }, Modifier.weight(1f), placeholder = "bastion.example.com")
                        Field("Port", jPort, { jPort = it.filter(Char::isDigit) }, Modifier.width(96.dp), number = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("User jump host", jUser, { jUser = it }, Modifier.weight(1f), placeholder = "kosong = sama")
                        KeyPicker("Kunci jump host", keys, jKey, allowSame = true, onPick = { jKey = it }, modifier = Modifier.weight(1f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Folder awal", workDir, { workDir = it }, Modifier.weight(1f), placeholder = "~ atau ~/proyek")
                    Field("Perintah Python", python, { python = it }, Modifier.weight(1f), placeholder = "python3 atau conda run -n env python")
                }
                err?.let { Text(it, color = Ide.deleted, style = Ide.small) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = build()
                err = p.validate()
                if (err == null) onSave(p)
            }) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

@Composable
private fun KeyDialog(onDismiss: () -> Unit, onGenerate: (String) -> Unit, onImport: (String, String) -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("tablet") }
    var text by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openInputStream(uri)!!.use { s ->
                    val buf = ByteArray(64 * 1024)
                    var n = 0
                    while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                    String(buf, 0, n, Charsets.UTF_8)
                }
            }.onSuccess { text = it }.onFailure { err = "Gagal membaca file: ${it.message}" }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.width(620.dp),
        title = { Text("Kunci SSH baru") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Nama kunci", name, { name = it }, Modifier.fillMaxWidth())
                FlatButton("Buat kunci Ed25519 baru (disarankan)") {
                    runCatching { onGenerate(name.trim().ifEmpty { "tablet" }) }.onFailure { err = it.message }
                }
                Text("atau impor private key yang sudah ada:", style = Ide.small.copy(color = Ide.textDim))
                OutlinedTextField(
                    text, { text = it }, label = { Text("Tempel isi private key (-----BEGIN …)") },
                    textStyle = Ide.small.copy(fontFamily = Ide.mono), modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 200.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlatButton("Pilih file…", primary = false) { picker.launch(arrayOf("*/*")) }
                    FlatButton("Impor", enabled = text.isNotBlank()) {
                        runCatching { onImport(name.trim().ifEmpty { "impor" }, text) }.onFailure { err = it.message }
                    }
                }
                err?.let { Text(it, color = Ide.deleted, style = Ide.small) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}

@Composable
private fun PublicKeyDialog(k: StoredKey, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val line = k.publicLine
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.width(640.dp),
        title = { Text("Public key: ${k.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (line == null) {
                    Text("Public key belum diketahui untuk format ini. Ia akan terbaca setelah kunci dipakai, atau salin dari file .pub Anda.", style = Ide.label)
                } else {
                    Text("Tambahkan baris ini ke ~/.ssh/authorized_keys di server (dan di jump host). Dari komputer yang sudah bisa login:",
                        style = Ide.label.copy(color = Ide.textDim))
                    Text("echo '<baris di bawah>' >> ~/.ssh/authorized_keys", style = Ide.small.copy(fontFamily = Ide.mono, color = Ide.textDim))
                    Text(line, style = Ide.small.copy(fontFamily = Ide.mono, color = Ide.text),
                        modifier = Modifier.fillMaxWidth().background(Ide.bg, Ide.itemShape).padding(12.dp))
                    k.fingerprint?.let { Text(it, style = Ide.small.copy(fontFamily = Ide.mono, color = Ide.textDim)) }
                }
            }
        },
        confirmButton = {
            if (line != null) TextButton(onClick = { copy(ctx, line); onDismiss() }) { Text("Salin") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}

@Composable
fun ConnectionOverlays(state: AppState) {
    state.progress?.let { p ->
        Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
            Row(
                Modifier.clip(Ide.cardShape).background(Ide.cardRaised).padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text("   $p", style = Ide.label)
            }
        }
    }
    state.hostKeyPrompt?.let { h -> HostKeyDialog(h, onAnswer = state::answerHostKey) }
    state.passphraseRequest?.let { r -> PassphraseDialog(r, onSubmit = state::submitPassphrases) }
    state.error?.let { e ->
        AlertDialog(
            onDismissRequest = { state.error = null },
            title = { Text("Tidak bisa tersambung") },
            text = { Text(e) },
            confirmButton = { TextButton(onClick = { state.error = null }) { Text("OK") } },
        )
    }
}

@Composable
fun HostKeyDialog(h: HostKeyPrompt, onAnswer: (Boolean) -> Unit) {
    val changed = h.previousFingerprint != null
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        icon = { if (changed) Icon(Icons.Outlined.Warning, null, tint = Ide.deleted) },
        title = { Text(if (changed) "PERINGATAN: host key ${h.host} berubah" else "Host baru: ${h.host}:${h.port}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (changed) "Sidik jari server berbeda dari yang dipercaya sebelumnya. Ini wajar bila server di-reinstall, tetapi bisa juga tanda penyadapan. Tanyakan admin server sebelum melanjutkan."
                    else "Tablet belum pernah tersambung ke host ini. Cocokkan sidik jari dengan yang diumumkan admin server (atau hasil ssh-keyscan dari komputer tepercaya).",
                    style = Ide.label,
                )
                h.previousFingerprint?.let { Text("Sebelumnya: $it", style = Ide.small.copy(fontFamily = Ide.mono, color = Ide.textDim)) }
                Text(h.fingerprint, style = Ide.small.copy(fontFamily = Ide.mono, color = Ide.textBright),
                    modifier = Modifier.background(Ide.bg, Ide.itemShape).padding(10.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(if (changed) "Percayai kunci baru" else "Percayai") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Batal") } },
    )
}

@Composable
private fun PassphraseDialog(r: PassphraseRequest, onSubmit: (Map<String, String>?) -> Unit) {
    val values = remember { mutableStateMapOf<String, String>() }
    AlertDialog(
        onDismissRequest = { onSubmit(null) },
        title = { Text("Passphrase kunci") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Passphrase hanya disimpan di memori sampai aplikasi ditutup.", style = Ide.small.copy(color = Ide.textDim))
                r.keys.forEach { k ->
                    OutlinedTextField(
                        values[k.id] ?: "", { values[k.id] = it }, label = { Text(k.name) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(values.toMap()) }) { Text("Sambungkan") } },
        dismissButton = { TextButton(onClick = { onSubmit(null) }) { Text("Batal") } },
    )
}
