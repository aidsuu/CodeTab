package app.jangkau.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.jangkau.core.GitChange
import app.jangkau.core.RemoteEntry
import app.jangkau.core.TermKey
import app.jangkau.core.pathName
import app.jangkau.core.pathParent
import app.jangkau.core.pathRelativeTo
import app.jangkau.editor.EditorSupport
import app.jangkau.editor.Languages
import app.jangkau.term.TerminalView
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.delay

private data class Ask(val title: String, val initial: String, val confirm: String, val onOk: (String) -> Unit)

private val Gap = 10.dp

@Composable
fun WorkspaceScreen(ws: WorkspaceState, onDisconnect: () -> Unit, onReconnect: () -> Unit) {
    var ask by remember { mutableStateOf<Ask?>(null) }
    var confirmDelete by remember { mutableStateOf<RemoteEntry?>(null) }
    val editors = remember { mutableMapOf<String, CodeEditor>() }

    LaunchedEffect(ws.message) {
        if (ws.message != null) { delay(3500); ws.message = null }
    }

    Column(Modifier.fillMaxSize().background(Ide.bg).imePadding().padding(start = Gap, end = Gap, top = Gap)) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Gap)) {
            ActivityBar(ws, onDisconnect)
            if (ws.sidebarVisible) {
                Column(Modifier.width(300.dp).fillMaxHeight().clip(Ide.cardShape).background(Ide.card)) {
                    when (ws.panel) {
                        SidePanel.Explorer -> ExplorerPanel(ws, onAsk = { ask = it }, onDelete = { confirmDelete = it })
                        SidePanel.Git -> GitPanel(ws)
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                val termHeight: Dp = if (ws.terminalMaximized) maxHeight else maxHeight * 0.4f
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Gap)) {
                    if (!(ws.terminalVisible && ws.terminalMaximized)) {
                        EditorArea(ws, editors, Modifier.weight(1f).clip(Ide.cardShape))
                    }
                    if (ws.terminalVisible) {
                        TerminalPanel(ws, Modifier.height(termHeight).clip(Ide.cardShape))
                    }
                }
            }
        }
        StatusBar(ws, onReconnect)
    }

    ws.message?.let { msg ->
        Box(Modifier.fillMaxSize().padding(bottom = 48.dp, end = 24.dp), contentAlignment = Alignment.BottomEnd) {
            Text(
                msg, style = Ide.label.copy(color = Ide.onPill, fontWeight = FontWeight.Medium),
                modifier = Modifier.clip(Ide.pillShape).background(Ide.pill).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }

    ask?.let { a -> AskDialog(a, onDismiss = { ask = null }) }
    confirmDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${e.name}?") },
            text = { Text(if (e.isDir) "The folder and everything in it will be permanently deleted from the server (rm -rf)." else "The file will be permanently deleted from the server.") },
            confirmButton = { TextButton(onClick = { ws.delete(e); confirmDelete = null }) { Text("Delete", color = Ide.deleted) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
    ws.saveConflict?.let { c ->
        AlertDialog(
            onDismissRequest = { ws.saveConflict = null },
            title = { Text("File changed on the server") },
            text = { Text("${c.tab.title} changed on the server after you opened it, maybe by a job or another editor. Overwrite it with the tablet version, or reload it from the server?") },
            confirmButton = { TextButton(onClick = { ws.saveConflict = null; ws.save(c.tab, overwrite = true) }) { Text("Overwrite") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { ws.saveConflict = null; editors.remove(c.tab.key); ws.reloadFromRemote(c.tab) }) { Text("Reload") }
                    TextButton(onClick = { ws.saveConflict = null }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun AskDialog(a: Ask, onDismiss: () -> Unit) {
    var value by remember(a) { mutableStateOf(a.initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(a.title) },
        text = { OutlinedTextField(value, { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = Ide.itemShape) },
        confirmButton = { TextButton(onClick = { onDismiss(); a.onOk(value.trim()) }, enabled = value.isNotBlank()) { Text(a.confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ActivityBar(ws: WorkspaceState, onDisconnect: () -> Unit) {
    var settings by remember { mutableStateOf(false) }
    Column(
        Modifier.width(60.dp).fillMaxHeight().clip(Ide.cardShape).background(Ide.card).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        fun pick(p: SidePanel) {
            if (ws.panel == p && ws.sidebarVisible) ws.sidebarVisible = false else { ws.panel = p; ws.sidebarVisible = true }
        }
        Row(Modifier.padding(bottom = 10.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf(Ide.textBright, Ide.textDim, Color(0xFF555555)).forEach { c ->
                Box(Modifier.size(7.dp).clip(Ide.pillShape).background(c))
            }
        }
        ActivityIcon(Icons.Outlined.FileCopy, "Explorer", ws.sidebarVisible && ws.panel == SidePanel.Explorer) { pick(SidePanel.Explorer) }
        ActivityIcon(
            Icons.Outlined.AccountTree, "Git", ws.sidebarVisible && ws.panel == SidePanel.Git,
            badge = ws.git.status?.changes?.size?.takeIf { it > 0 },
        ) { pick(SidePanel.Git); ws.refreshGit() }
        ActivityIcon(Icons.Outlined.Terminal, "Terminal", ws.terminalVisible) { ws.terminalVisible = !ws.terminalVisible }
        Spacer(Modifier.weight(1f))
        Box {
            ActivityIcon(Icons.Outlined.Settings, "Settings", settings) { settings = true }
            SettingsMenu(ws, settings) { settings = false }
        }
        ActivityIcon(Icons.Outlined.Logout, "Disconnect", false, onClick = onDisconnect)
    }
}

@Composable
private fun SettingsMenu(ws: WorkspaceState, open: Boolean, onClose: () -> Unit) {
    val p = ws.prefs
    DropdownMenu(open, onClose, shape = Ide.itemShape, containerColor = Ide.cardRaised) {
        Text("EDITOR & TERMINAL FONT", style = Ide.section, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        MonoFont.entries.forEach { f ->
            DropdownMenuItem(
                text = { Text(f.label) },
                leadingIcon = { if (p.font == f) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) else Spacer(Modifier.size(18.dp)) },
                onClick = { p.font = f },
            )
        }
        HorizontalDivider(color = Ide.border)
        DropdownMenuItem(
            text = { Text(if (p.showHidden) "Hide dotfiles" else "Show dotfiles") },
            leadingIcon = { Icon(if (p.showHidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null, Modifier.size(18.dp)) },
            onClick = { p.showHidden = !p.showHidden },
        )
        DropdownMenuItem(
            text = { Text("Terminal font size: ${p.terminalTextSp.toInt()} sp") },
            leadingIcon = { Spacer(Modifier.size(18.dp)) },
            trailingIcon = {
                Row {
                    SmallIcon(Icons.Outlined.TextDecrease, "Zoom out") { p.terminalTextSp -= 1f }
                    SmallIcon(Icons.Outlined.TextIncrease, "Zoom in") { p.terminalTextSp += 1f }
                }
            },
            onClick = {},
        )
    }
}

@Composable
private fun ActivityIcon(icon: ImageVector, label: String, active: Boolean, badge: Int? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (active) Ide.selection else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (active) Ide.textBright else Ide.textDim, modifier = Modifier.size(22.dp))
        if (badge != null) {
            Text(
                if (badge > 99) "99+" else "$badge",
                style = TextStyle(fontFamily = Ide.ui, fontSize = 9.sp, color = Ide.onPill, fontWeight = FontWeight.Bold),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 3.dp)
                    .clip(Ide.pillShape).background(Ide.pill).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun PanelHeader(title: String, actions: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Ide.title, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun SmallIcon(icon: ImageVector, label: String, tint: Color = Ide.text, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (enabled) tint else Ide.textDim.copy(alpha = 0.35f), modifier = Modifier.size(18.dp)) }
}

@Composable
fun PillSearch(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Row(
        modifier.height(38.dp).clip(Ide.pillShape).background(Ide.field).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = Ide.textDim, modifier = Modifier.size(17.dp))
        Box(Modifier.weight(1f).padding(start = 8.dp)) {
            if (value.isEmpty()) Text(placeholder, style = Ide.label.copy(color = Ide.textDim))
            BasicTextField(value, onChange, singleLine = true, textStyle = Ide.label, cursorBrush = SolidColor(Ide.text), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) {
            Icon(Icons.Outlined.Close, "Delete", tint = Ide.textDim, modifier = Modifier.size(16.dp).clickable { onChange("") })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExplorerPanel(ws: WorkspaceState, onAsk: (Ask) -> Unit, onDelete: (RemoteEntry) -> Unit) {
    val ctx = LocalContext.current
    PanelHeader("Explorer") {
        SmallIcon(Icons.AutoMirrored.Outlined.NoteAdd, "New file") {
            onAsk(Ask("New file in ${pathName(ws.root)}", "", "Create") { ws.createFile(ws.root, it) })
        }
        SmallIcon(Icons.Outlined.CreateNewFolder, "New folder") {
            onAsk(Ask("New folder in ${pathName(ws.root)}", "", "Create") { ws.createFolder(ws.root, it) })
        }
        SmallIcon(Icons.Outlined.Refresh, "Reload") { ws.refreshAll() }
        SmallIcon(Icons.Outlined.UnfoldLess, "Collapse all") { ws.collapseAll() }
    }
    PillSearch(ws.filter, { ws.filter = it }, "Search files", Modifier.fillMaxWidth().padding(horizontal = 14.dp))
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clip(Ide.itemShape)
                .clickable { onAsk(Ask("Open folder on the server", ws.root, "Open") { ws.openFolder(it) }) }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(pathName(ws.root).uppercase(), style = Ide.section.copy(color = Ide.text, fontWeight = FontWeight.Bold),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Outlined.DriveFileMove, "Change folder", tint = Ide.textDim, modifier = Modifier.padding(start = 6.dp).size(15.dp))
        }
        SmallIcon(
            if (ws.prefs.showHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
            if (ws.prefs.showHidden) "Hide dotfiles" else "Show dotfiles",
            tint = if (ws.prefs.showHidden) Ide.text else Ide.textDim,
        ) { ws.prefs.showHidden = !ws.prefs.showHidden }
    }
    val rows = ws.rows()
    when {
        !ws.rootLoaded -> Text("Loading ${ws.root}…", style = Ide.small.copy(color = Ide.textDim), modifier = Modifier.padding(18.dp))
        rows.isEmpty() && ws.filter.isNotBlank() -> Text("No files match \"${ws.filter}\" in the folders opened so far.", style = Ide.small.copy(color = Ide.textDim), modifier = Modifier.padding(18.dp))
        rows.isEmpty() -> Text("Empty folder", style = Ide.small.copy(color = Ide.textDim), modifier = Modifier.padding(18.dp))
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        items(rows, key = { it.entry.path }) { row ->
            var menu by remember { mutableStateOf(false) }
            val e = row.entry
            val active = ws.activeKey == e.path
            val selected = active || ws.selectedPath == e.path
            val fg = if (active) Ide.onPill else if (e.name.startsWith(".")) Ide.textDim else Ide.text
            Box {
                Row(
                    Modifier.fillMaxWidth().height(32.dp).clip(Ide.itemShape)
                        .background(if (active) Ide.pill else if (selected) Ide.selection else Color.Transparent)
                        .combinedClickable(onClick = { ws.toggle(e) }, onLongClick = { ws.selectedPath = e.path; menu = true })
                        .padding(start = (8 + row.depth * 14).dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (e.isDir) {
                        Icon(if (row.expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, null,
                            tint = if (active) Ide.onPill else Ide.textDim, modifier = Modifier.size(16.dp))
                    } else Spacer(Modifier.width(16.dp))
                    val (icon, tint) = fileIcon(e.name, e.isDir, row.expanded)
                    Icon(icon, null, tint = if (active) Ide.onPill else tint, modifier = Modifier.padding(start = 2.dp, end = 8.dp).size(16.dp))
                    Text(e.name, style = Ide.label.copy(color = fg, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    row.hint?.let {
                        Text("  $it", style = Ide.small.copy(color = if (active) Ide.onPill.copy(alpha = 0.6f) else Ide.textDim),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.weight(1f))
                    gitBadge(ws, e)?.let { (letter, color) ->
                        Text(letter, style = Ide.small.copy(color = if (active) Ide.onPill else color, fontWeight = FontWeight.SemiBold))
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = Ide.itemShape, containerColor = Ide.cardRaised) {
                    val dir = if (e.isDir) e.path else pathParent(e.path)
                    if (!e.isDir && Languages.runKind(e.name) != null) {
                        DropdownMenuItem(text = { Text("Run in terminal") }, onClick = { menu = false; ws.runFile(e.path) })
                        DropdownMenuItem(text = { Text("Submit to SLURM (sbatch)") }, onClick = { menu = false; ws.runFile(e.path, slurm = true) })
                    }
                    DropdownMenuItem(text = { Text("New file…") }, onClick = {
                        menu = false; onAsk(Ask("New file in ${pathName(dir)}", "", "Create") { ws.createFile(dir, it) })
                    })
                    DropdownMenuItem(text = { Text("New folder…") }, onClick = {
                        menu = false; onAsk(Ask("New folder in ${pathName(dir)}", "", "Create") { ws.createFolder(dir, it) })
                    })
                    if (e.isDir) DropdownMenuItem(text = { Text("Set as working folder") }, onClick = { menu = false; ws.openFolder(e.path) })
                    DropdownMenuItem(text = { Text("Open terminal here") }, onClick = { menu = false; ws.cdInTerminal(dir) })
                    DropdownMenuItem(text = { Text("Rename…") }, onClick = {
                        menu = false; onAsk(Ask("Rename ${e.name}", e.name, "Rename") { ws.rename(e, it) })
                    })
                    DropdownMenuItem(text = { Text("Copy path") }, onClick = { menu = false; copy(ctx, e.path); ws.message = "Path copied" })
                    DropdownMenuItem(text = { Text("Delete…", color = Ide.deleted) }, onClick = { menu = false; onDelete(e) })
                }
            }
        }
    }
}

private fun fileIcon(name: String, isDir: Boolean, expanded: Boolean): Pair<ImageVector, Color> = when {
    isDir -> (if (expanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder) to Ide.folder
    Languages.viewer(name) == Languages.Viewer.Image -> Icons.Outlined.Image to Ide.media
    Languages.viewer(name) == Languages.Viewer.Pdf -> Icons.Outlined.PictureAsPdf to Ide.deleted
    else -> Icons.Outlined.Description to when (Languages.forName(name).label) {
        "Python" -> Ide.python
        "Shell" -> Ide.shell
        "JSON", "YAML" -> Ide.data
        "Markdown" -> Color(0xFF7FB7D9)
        else -> Ide.textDim
    }
}

private fun gitBadge(ws: WorkspaceState, e: RemoteEntry): Pair<String, Color>? {
    val repo = ws.git.repo ?: return null
    val rel = pathRelativeTo(e.path, repo) ?: return null
    val changes = ws.git.status?.changes ?: return null
    if (e.isDir) return if (changes.any { it.path.startsWith("$rel/") }) "•" to Ide.modified else null
    return changes.firstOrNull { it.path == rel }?.let(::changeLetter)
}

private fun changeLetter(c: GitChange): Pair<String, Color> = when {
    c.conflicted -> "!" to Ide.conflict
    c.untracked -> "U" to Ide.untracked
    c.index == "A" -> "A" to Ide.added
    c.index == "D" || c.worktree == "D" -> "D" to Ide.deleted
    c.index == "R" -> "R" to Ide.added
    else -> "M" to Ide.modified
}

fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("codetab", text))
}

private fun clipboardText(ctx: Context): String? =
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()

@Composable
private fun ColumnScope.GitPanel(ws: WorkspaceState) {
    val g = ws.git
    PanelHeader("Source Control") {
        SmallIcon(Icons.Outlined.ArrowDownward, "Pull", enabled = !g.busy && g.repo != null) { ws.gitPull() }
        SmallIcon(Icons.Outlined.ArrowUpward, "Push", enabled = !g.busy && g.repo != null) { ws.gitPush() }
        SmallIcon(Icons.Outlined.Sync, "Fetch", enabled = !g.busy && g.repo != null) { ws.gitFetch() }
        SmallIcon(Icons.Outlined.Refresh, "Reload") { ws.refreshGit() }
    }
    if (g.notRepo) {
        Column(Modifier.padding(18.dp)) {
            Text("${pathName(ws.root)} is not a Git repository yet.", style = Ide.label.copy(color = Ide.textDim))
            Spacer(Modifier.height(12.dp))
            FlatButton("git init", onClick = { ws.gitInit() })
        }
        return
    }
    val st = g.status
    Column(Modifier.padding(horizontal = 14.dp)) {
        if (st != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)) {
                Icon(Icons.Outlined.AccountTree, null, tint = Ide.textDim, modifier = Modifier.size(14.dp))
                Text(
                    buildString {
                        append("  ").append(st.branch)
                        st.upstream?.let { append("  →  ").append(it) }
                        if (st.ahead > 0u) append("  ↑").append(st.ahead)
                        if (st.behind > 0u) append("  ↓").append(st.behind)
                    },
                    style = Ide.small.copy(color = Ide.textDim), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        BasicTextField(
            g.commitMessage, { g.commitMessage = it },
            textStyle = Ide.label, cursorBrush = SolidColor(Ide.text),
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(Ide.itemShape).background(Ide.field)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            decorationBox = { inner ->
                if (g.commitMessage.isEmpty()) Text("Commit message (on ${st?.branch ?: "…"})", style = Ide.label.copy(color = Ide.textDim))
                inner()
            },
        )
        Spacer(Modifier.height(10.dp))
        FlatButton(if (g.busy) "Working…" else "Commit", enabled = !g.busy && st?.changes?.any { it.staged } == true,
            modifier = Modifier.fillMaxWidth()) { ws.gitCommit() }
        Spacer(Modifier.height(8.dp))
    }
    val changes = st?.changes.orEmpty()
    val staged = changes.filter { it.staged }
    val unstaged = changes.filter { it.unstaged || it.untracked || it.conflicted }
    LazyColumn(Modifier.weight(1f).padding(horizontal = 6.dp)) {
        if (staged.isNotEmpty()) {
            item { GitSection("Staged", staged.size) { SmallIcon(Icons.Outlined.Remove, "Unstage all") { ws.gitUnstage(staged.map { it.path }) } } }
            items(staged, key = { "s:" + it.path }) { c -> GitRow(ws, c, staged = true) }
        }
        item {
            GitSection("Changes", unstaged.size) {
                if (unstaged.isNotEmpty()) SmallIcon(Icons.Outlined.Add, "Stage all") { ws.gitStage(unstaged.map { it.path }) }
            }
        }
        items(unstaged, key = { "w:" + it.path }) { c -> GitRow(ws, c, staged = false) }
        if (st != null && changes.isEmpty()) {
            item { Text("No changes.", style = Ide.small.copy(color = Ide.textDim), modifier = Modifier.padding(16.dp)) }
        }
    }
    if (g.output.isNotBlank()) {
        Text(
            g.output.takeLast(2000), style = Ide.small.copy(fontFamily = Ide.mono, fontSize = 11.sp, color = Ide.textDim),
            modifier = Modifier.fillMaxWidth().padding(10.dp).clip(Ide.itemShape).background(Ide.bg)
                .heightIn(max = 140.dp).verticalScroll(rememberScrollState()).padding(10.dp),
        )
    }
}

@Composable
private fun GitSection(title: String, count: Int, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(34.dp).padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Ide.label.copy(fontWeight = FontWeight.SemiBold, color = Ide.textBright), modifier = Modifier.weight(1f))
        actions()
        Text(
            "$count", style = TextStyle(fontFamily = Ide.ui, fontSize = 11.sp, color = Ide.text),
            modifier = Modifier.padding(start = 4.dp).clip(Ide.pillShape).background(Ide.selection).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun GitRow(ws: WorkspaceState, c: GitChange, staged: Boolean) {
    val (letter, color) = changeLetter(c)
    var confirm by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(32.dp).clip(Ide.itemShape).clickable { ws.openDiff(c, staged) }.padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (icon, tint) = fileIcon(pathName(c.path), false, false)
        Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
        Text(pathName(c.path), style = Ide.label.copy(color = color), maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        val dir = pathParent(c.path).takeIf { it != "." }
        Text(dir ?: "", style = Ide.small.copy(color = Ide.textDim), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp).weight(1f))
        if (!staged && !c.untracked) SmallIcon(Icons.AutoMirrored.Outlined.Undo, "Discard changes") { confirm = true }
        if (!staged && !c.untracked) SmallIcon(Icons.Outlined.FileCopy, "Open file") { ws.open(ws.absolute(c.path)) }
        if (staged) SmallIcon(Icons.Outlined.Remove, "Unstage") { ws.gitUnstage(listOf(c.path)) }
        else SmallIcon(Icons.Outlined.Add, "Stage") { ws.gitStage(listOf(c.path)) }
        Text(letter, style = Ide.small.copy(color = color, fontWeight = FontWeight.Bold), modifier = Modifier.width(14.dp))
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Discard changes to ${pathName(c.path)}?") },
            text = { Text("The file is restored to the last committed version (git checkout). This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirm = false; ws.gitDiscard(listOf(c.path)) }) { Text("Discard", color = Ide.deleted) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun FlatButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = true, onClick: () -> Unit) {
    val bg = if (primary) Ide.pill else Ide.selection
    val fg = if (primary) Ide.onPill else Ide.textBright
    Box(
        modifier.clip(Ide.pillShape).background(if (enabled) bg else bg.copy(alpha = 0.3f))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Ide.label.copy(color = fg.copy(alpha = if (enabled) 1f else 0.6f), fontWeight = FontWeight.SemiBold)) }
}

@Composable
private fun EditorArea(ws: WorkspaceState, editors: MutableMap<String, CodeEditor>, modifier: Modifier) {
    val tab = ws.activeTab
    LaunchedEffect(ws.tabs.size) {
        val keys = ws.tabs.map { it.key }.toSet()
        editors.keys.retainAll(keys)
    }
    Column(modifier.fillMaxWidth().background(Ide.card)) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 8.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ws.tabs.forEach { t -> EditorTabChip(ws, t) }
            }
            if (tab != null && tab.viewer == null) {
                val runnable = !tab.readOnly && Languages.runKind(tab.title) != null
                var runMenu by remember { mutableStateOf(false) }
                if (runnable) {
                    Row(
                        Modifier.clip(Ide.pillShape).background(Ide.pill).clickable { ws.runFile(tab.path) }
                            .padding(start = 12.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.PlayArrow, null, tint = Ide.onPill, modifier = Modifier.size(16.dp))
                        Text(" Run", style = Ide.small.copy(color = Ide.onPill, fontWeight = FontWeight.SemiBold))
                    }
                    Box {
                        SmallIcon(Icons.Outlined.KeyboardArrowDown, "Run options") { runMenu = true }
                        DropdownMenu(runMenu, { runMenu = false }, shape = Ide.itemShape, containerColor = Ide.cardRaised) {
                            DropdownMenuItem(text = { Text("Run in terminal (Ctrl+Enter)") }, onClick = { runMenu = false; ws.runFile(tab.path) })
                            DropdownMenuItem(text = { Text("Submit to SLURM (sbatch)") }, onClick = { runMenu = false; ws.runFile(tab.path, slurm = true) })
                        }
                    }
                }
                SmallIcon(Icons.Outlined.Save, "Save", enabled = !tab.readOnly && tab.dirty && !tab.saving) { ws.save(tab) }
                SmallIcon(Icons.AutoMirrored.Outlined.Undo, "Undo", enabled = !tab.readOnly) { tab.undo?.invoke() }
                SmallIcon(Icons.AutoMirrored.Outlined.Redo, "Redo", enabled = !tab.readOnly) { tab.redo?.invoke() }
                SmallIcon(Icons.Outlined.TextDecrease, "Zoom out") { ws.editorTextSp -= 1f }
                SmallIcon(Icons.Outlined.TextIncrease, "Zoom in") { ws.editorTextSp += 1f }
            }
        }
        if (tab == null) {
            Welcome(ws)
            return@Column
        }
        val crumb = (if (tab.key.startsWith("diff:")) tab.key.substringAfter(':').substringAfter(':') else pathRelativeTo(tab.path, ws.root) ?: tab.path)
            .split('/').filter { it.isNotEmpty() && it != "." }.joinToString("  ›  ")
        Text(crumb, style = Ide.small.copy(color = Ide.textDim), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp))
        when (tab.viewer) {
            Languages.Viewer.Image -> androidx.compose.runtime.key(tab.key) { ImageViewer(tab) }
            Languages.Viewer.Pdf -> androidx.compose.runtime.key(tab.key) { PdfViewer(tab) }
            null -> {
                val ctx = LocalContext.current
                val font = ws.prefs.font
                androidx.compose.runtime.key(tab.key) {
                    AndroidView(
                        factory = {
                            val ed = editors.getOrPut(tab.key) {
                                EditorSupport.create(
                                    ctx, tab.title, tab.initialText, tab.readOnly, ws.editorTextSp, font.typeface(ctx),
                                    onChanged = { tab.dirty = true },
                                    onCursor = { l, c -> tab.cursorLine = l; tab.cursorCol = c },
                                )
                            }
                            (ed.parent as? ViewGroup)?.removeView(ed)
                            tab.currentText = { ed.text.toString() }
                            tab.undo = { ed.undo() }
                            tab.redo = { ed.redo() }
                            ed
                        },
                        update = { ed ->
                            ed.setTextSize(ws.editorTextSp)
                            val tf = font.typeface(ctx)
                            if (ed.typefaceText != tf) { ed.typefaceText = tf; ed.typefaceLineNumber = tf }
                        },
                        modifier = Modifier.fillMaxSize().padding(start = 6.dp, bottom = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorTabChip(ws: WorkspaceState, t: EditorTab) {
    val active = t.key == ws.activeKey
    Row(
        Modifier.height(36.dp).clip(Ide.itemShape).background(if (active) Ide.tabActive else Ide.tabInactive)
            .clickable { ws.activeKey = t.key }.padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (icon, tint) = fileIcon(t.title, false, false)
        Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
        Text(
            t.title,
            style = Ide.label.copy(
                color = if (active) Ide.textBright else Ide.textDim,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                fontStyle = if (t.readOnly && t.viewer == null) FontStyle.Italic else null,
            ),
            maxLines = 1, modifier = Modifier.padding(start = 8.dp, end = 4.dp),
        )
        Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).clickable { ws.closeTab(t) }, contentAlignment = Alignment.Center) {
            if (t.dirty) Box(Modifier.size(8.dp).clip(Ide.pillShape).background(Ide.text))
            else Icon(Icons.Outlined.Close, "Close", tint = if (active) Ide.text else Ide.textDim, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun Welcome(ws: WorkspaceState) {
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("CodeTab", style = Ide.hero.copy(color = Color(0xFF3A3A3A)))
        Spacer(Modifier.height(6.dp))
        Text("All files, processes, and Python run on ${ws.remote.label}", style = Ide.label.copy(color = Ide.textDim))
        Spacer(Modifier.height(28.dp))
        listOf(
            "Open file" to "tap in Explorer",
            "Save" to "Ctrl + S",
            "Run file" to "Ctrl + Enter",
            "Toggle terminal" to "Ctrl + J",
            "Toggle sidebar" to "Ctrl + B",
            "File menu" to "long press in Explorer",
        ).forEach { (a, b) ->
            Row(Modifier.width(380.dp).padding(vertical = 4.dp)) {
                Text(a, style = Ide.label.copy(color = Ide.textDim), modifier = Modifier.weight(1f))
                Text(b, style = Ide.label.copy(color = Ide.text, fontFamily = Ide.mono, fontSize = 12.sp))
            }
        }
    }
}

@Composable
private fun TerminalPanel(ws: WorkspaceState, modifier: Modifier) {
    val ctx = LocalContext.current
    var view by remember { mutableStateOf<TerminalView?>(null) }
    val term = ws.terminals.getOrNull(ws.activeTerminal)
    LaunchedEffect(Unit) {
        if (ws.terminals.isEmpty() && ws.remote.isConnected) ws.newTerminal()
    }
    Column(modifier.fillMaxWidth().background(Ide.card)) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Terminal", style = Ide.title.copy(fontSize = 16.sp))
            Spacer(Modifier.width(14.dp))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ws.terminals.forEachIndexed { i, t ->
                    val active = i == ws.activeTerminal
                    Row(
                        Modifier.clip(Ide.pillShape).background(if (active) Ide.pill else Ide.selection)
                            .clickable { ws.activeTerminal = i }.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Terminal, null, tint = if (active) Ide.onPill else if (t.exited != null) Ide.textDim else Ide.text,
                            modifier = Modifier.size(13.dp))
                        Text(t.title, style = Ide.small.copy(color = if (active) Ide.onPill else Ide.textDim, fontWeight = FontWeight.Medium),
                            modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            SmallIcon(Icons.Outlined.Add, "New terminal", enabled = !ws.openingTerminal) { ws.newTerminal() }
            SmallIcon(Icons.Outlined.ContentPaste, "Paste") { clipboardText(ctx)?.let { term?.paste(it) } }
            SmallIcon(Icons.Outlined.ContentCopy, "Copy screen") { term?.let { copy(ctx, it.terminal.screenText()); ws.message = "Screen copied" } }
            SmallIcon(if (ws.terminalMaximized) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp, "Maximize panel") {
                ws.terminalMaximized = !ws.terminalMaximized
            }
            SmallIcon(Icons.Outlined.Close, "Close terminal", enabled = term != null) { term?.let { ws.closeTerminal(it) } }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 4.dp)) {
            if (term == null) {
                Text(if (ws.openingTerminal) "Opening a shell on the server…" else "No terminals yet. Tap + to open a shell.",
                    style = Ide.label.copy(color = Ide.textDim), modifier = Modifier.padding(8.dp))
            } else {
                val font = ws.prefs.font
                val size = ws.prefs.terminalTextSp
                androidx.compose.runtime.key(term.id) {
                    AndroidView(
                        factory = { TerminalView(it).also { v -> view = v } },
                        update = { v ->
                            v.setFonts(font.typeface(ctx), font.typeface(ctx, bold = true))
                            v.textSizeSp = size
                            if (v.source !== term) v.source = term
                            term.onUpdate = { v.refresh() }
                            view = v
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    DisposableEffect(term) { onDispose { term.onUpdate = null } }
                }
                term.exited?.let {
                    Text(
                        "$it  ·  tap + for a new shell", style = Ide.small.copy(color = Ide.warning),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(Ide.pillShape).background(Ide.bg).padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (term != null) ExtraKeys(term, view)
    }
}

@Composable
private fun ExtraKeys(term: TermTab, view: TerminalView?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        @Composable
        fun k(label: String, active: Boolean = false, onClick: () -> Unit) {
            Box(
                Modifier.height(32.dp).width(if (label.length > 2) 56.dp else 40.dp)
                    .clip(Ide.pillShape).background(if (active) Ide.pill else Ide.field).clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = Ide.small.copy(fontFamily = Ide.mono, color = if (active) Ide.onPill else Ide.text)) }
        }
        k("ESC") { view?.sendKey(TermKey.Escape) }
        k("TAB") { view?.sendKey(TermKey.Tab) }
        k("CTRL", term.stickyCtrl) { term.stickyCtrl = !term.stickyCtrl }
        k("ALT", term.stickyAlt) { term.stickyAlt = !term.stickyAlt }
        k("←") { view?.sendKey(TermKey.Left) }
        k("↑") { view?.sendKey(TermKey.Up) }
        k("↓") { view?.sendKey(TermKey.Down) }
        k("→") { view?.sendKey(TermKey.Right) }
        k("HOME") { view?.sendKey(TermKey.Home) }
        k("END") { view?.sendKey(TermKey.End) }
        k("PGUP") { view?.sendKey(TermKey.PageUp) }
        k("PGDN") { view?.sendKey(TermKey.PageDown) }
        k("^C") { term.type("\u0003") }
        k("^D") { term.type("\u0004") }
        k("^Z") { term.type("\u001a") }
        listOf("|", "~", "/", "-", "_", "$", "&", ">", "*").forEach { s -> k(s) { term.type(s) } }
    }
}

@Composable
private fun StatusBar(ws: WorkspaceState, onReconnect: () -> Unit) {
    val lost = ws.connectionLost
    Row(
        Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val dim = Ide.small.copy(color = Ide.textDim)
        Row(
            Modifier.clip(Ide.pillShape).background(if (lost) Ide.statusBarOffline else Ide.card)
                .clickable(enabled = lost, onClick = onReconnect).padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(Ide.pillShape).background(if (lost) Ide.deleted else Ide.added))
            Icon(if (lost) Icons.Outlined.CloudOff else Icons.Outlined.Cloud, null, tint = Ide.text, modifier = Modifier.padding(start = 8.dp).size(14.dp))
            Text(if (lost) "  Disconnected · tap to reconnect" else "  ${ws.remote.label}", style = Ide.small)
        }
        ws.git.status?.let { st ->
            Row(Modifier.clickable { ws.panel = SidePanel.Git; ws.sidebarVisible = true }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AccountTree, null, tint = Ide.textDim, modifier = Modifier.size(13.dp))
                Text(" ${st.branch}${if (st.changes.isNotEmpty()) "*" else ""}" +
                    (if (st.ahead > 0u || st.behind > 0u) "  ↓${st.behind} ↑${st.ahead}" else ""), style = dim)
            }
        }
        Spacer(Modifier.weight(1f))
        ws.activeTab?.let { t ->
            if (t.viewer == null) {
                Text("Ln ${t.cursorLine}, Col ${t.cursorCol}", style = dim)
                Text("Spaces: 4", style = dim)
                Text("UTF-8", style = dim)
            }
            Text(t.language, style = dim)
            if (Languages.runKind(t.title) == Languages.RunKind.Python) Text(ws.profile.pythonCmd, style = dim)
        }
        Text(ws.prefs.font.label.substringBefore(" ("), style = dim)
    }
}
