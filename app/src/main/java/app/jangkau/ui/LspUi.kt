package app.jangkau.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.jangkau.core.LspDiagnostic
import app.jangkau.core.LspServerSpec
import app.jangkau.editor.Languages
import app.jangkau.lsp.LspStatus

/** Language server state for the active file, shown in the status bar. */
@Composable
fun LspStatusItem(ws: WorkspaceState, onClick: () -> Unit) {
    val tab = ws.activeTab ?: return
    val status = ws.lsp.statusFor(tab.lsp?.lspId) ?: return
    val (dot, text) = when (status) {
        LspStatus.Off, LspStatus.Unsupported -> return
        LspStatus.Detecting -> Ide.textDim to "Looking for a language server…"
        is LspStatus.Starting -> Ide.warning to "Starting ${status.name}…"
        is LspStatus.Ready -> Ide.added to (status.progress?.let { "${status.name} · $it" } ?: status.name)
        is LspStatus.Missing -> Ide.warning to "No language server · set up"
        is LspStatus.Failed -> Ide.deleted to "${status.name} failed · details"
    }
    Row(
        Modifier.clip(Ide.pillShape).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(Ide.pillShape).background(dot))
        Text(" $text", style = Ide.small.copy(color = Ide.textDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ProblemCounts(ws: WorkspaceState, onClick: () -> Unit) {
    val tab = ws.activeTab ?: return
    if (tab.lsp == null) return
    val diags = ws.lsp.diagnostics[tab.path].orEmpty()
    val errors = diags.count { it.severity.toInt() == 1 }
    val warnings = diags.count { it.severity.toInt() == 2 }
    Row(Modifier.clip(Ide.pillShape).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp)) {
        Text("✕ $errors", style = Ide.small.copy(color = if (errors > 0) Ide.deleted else Ide.textDim))
        Text("  ⚠ $warnings", style = Ide.small.copy(color = if (warnings > 0) Ide.warning else Ide.textDim))
    }
}

@Composable
fun SignatureStrip(ws: WorkspaceState) {
    val sig = ws.signature ?: return
    val label = sig.label
    val s = sig.activeStart?.toInt()?.coerceIn(0, label.length)
    val e = sig.activeEnd?.toInt()?.coerceIn(s ?: 0, label.length)
    val text = buildAnnotatedString {
        if (s != null && e != null && e > s) {
            append(label.substring(0, s))
            withStyle(SpanStyle(color = Ide.accent, fontWeight = FontWeight.Bold)) { append(label.substring(s, e)) }
            append(label.substring(e))
        } else {
            append(label)
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).clip(Ide.itemShape).background(Ide.cardRaised)
            .clickable { ws.signature = null }.padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, style = Ide.small.copy(fontFamily = Ide.mono, color = Ide.textBright), maxLines = 3, overflow = TextOverflow.Ellipsis)
        sig.documentation?.let { doc ->
            Text(plainMarkdown(doc), style = Ide.small.copy(color = Ide.textDim), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Drops Markdown fences and emphasis so hover text reads well in a plain dialog. */
fun plainMarkdown(md: String): String = md.lines()
    .filterNot { it.trim().startsWith("```") }
    .joinToString("\n") { it.replace("**", "").replace(Regex("(?<!\\w)_([^_]+)_(?!\\w)"), "$1").replace("`", "") }
    .replace(Regex("\n{3,}"), "\n\n")
    .replace("\\_", "_").replace("\\*", "*").replace("&nbsp;", " ")
    .trim()

@Composable
fun HoverDialog(ws: WorkspaceState) {
    val text = ws.hover ?: return
    AlertDialog(
        onDismissRequest = { ws.hover = null },
        confirmButton = { TextButton(onClick = { ws.hover = null }) { Text("Close") } },
        text = {
            SelectionContainer {
                Text(plainMarkdown(text), style = Ide.label.copy(fontFamily = Ide.mono),
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
            }
        },
    )
}

@Composable
fun ProblemsDialog(ws: WorkspaceState, onDismiss: () -> Unit) {
    val tab = ws.activeTab ?: run { onDismiss(); return }
    val diags = ws.lsp.diagnostics[tab.path].orEmpty().sortedWith(compareBy<LspDiagnostic> { it.severity }.thenBy { it.range.start.line })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Problems in ${tab.title}") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        text = {
            if (diags.isEmpty()) Text("No problems reported.", style = Ide.label.copy(color = Ide.textDim))
            else Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                diags.forEach { d ->
                    val color = when (d.severity.toInt()) { 1 -> Ide.deleted; 2 -> Ide.warning; else -> Ide.textDim }
                    Row(
                        Modifier.fillMaxWidth().clip(Ide.itemShape).clickable {
                            onDismiss()
                            tab.jumpTo?.invoke(d.range.start.line.toInt(), d.range.start.character.toInt())
                        }.padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(if (d.severity.toInt() == 1) "✕" else if (d.severity.toInt() == 2) "⚠" else "ℹ", style = Ide.label.copy(color = color),
                            modifier = Modifier.padding(end = 8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.message, style = Ide.label)
                            Text(listOfNotNull("Ln ${d.range.start.line + 1u}", d.source, d.code).joinToString(" · "),
                                style = Ide.small.copy(color = Ide.textDim))
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun LanguageServerDialog(ws: WorkspaceState, onDismiss: () -> Unit) {
    val tab = ws.activeTab ?: run { onDismiss(); return }
    val lspId = tab.lsp?.lspId ?: run { onDismiss(); return }
    val label = Languages.forName(tab.title).label
    val status = ws.lsp.statusFor(lspId)
    val candidates = ws.lsp.candidates(lspId)
    val installed = ws.lsp.probe?.binaries.orEmpty()
    val ctx = LocalContext.current
    var custom by remember { mutableStateOf(ws.prefs.customServer(lspId).orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$label language server") },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        confirmButton = {
            TextButton(onClick = {
                ws.prefs.setCustomServer(lspId, custom)
                ws.lsp.retry(lspId)
                onDismiss()
            }) { Text("Retry") }
        },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(statusText(status), style = Ide.label)
                if (status is LspStatus.Failed) {
                    SelectionContainer {
                        Text(status.message, style = Ide.small.copy(fontFamily = Ide.mono, color = Ide.deleted))
                    }
                }
                if (candidates.isNotEmpty()) {
                    Text("Servers CodeTab can use, in order of preference. Install one on the server, then tap Retry.",
                        style = Ide.small.copy(color = Ide.textDim))
                    candidates.forEach { c -> CandidateRow(ws, c, c.binary in installed, onCopy = { copy(ctx, c.install); ws.message = "Command copied" }) }
                }
                Text("Custom command", style = Ide.section)
                OutlinedTextField(
                    value = custom, onValueChange = { custom = it }, singleLine = true,
                    placeholder = { Text("for example  my-language-server --stdio") },
                    textStyle = Ide.label.copy(fontFamily = Ide.mono),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Runs in a login shell in the project folder and talks LSP over stdin and stdout. It is tried before the servers above.",
                    style = Ide.small.copy(color = Ide.textDim))
            }
        },
    )
}

@Composable
private fun CandidateRow(ws: WorkspaceState, c: LspServerSpec, installed: Boolean, onCopy: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(Ide.itemShape).background(Ide.cardRaised).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.name, style = Ide.label.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            Text(if (installed) "installed" else "not found", style = Ide.small.copy(color = if (installed) Ide.added else Ide.textDim))
        }
        if (!installed) {
            SelectionContainer { Text(c.install, style = Ide.small.copy(fontFamily = Ide.mono, color = Ide.textDim)) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCopy) { Text("Copy") }
                if (c.install.isShellCommand()) {
                    TextButton(onClick = { ws.typeInTerminal(c.install) }) { Text("Paste in terminal") }
                }
            }
        }
    }
}

private fun String.isShellCommand() = !contains(' ') || split(' ').first().all { it.isLetterOrDigit() || it in "-_." } &&
    !startsWith("Install") && !startsWith("Download") && !startsWith("Build")

private fun statusText(status: LspStatus?): String = when (status) {
    null, LspStatus.Detecting -> "Looking for an installed language server…"
    LspStatus.Off -> "Language servers are turned off in Settings."
    LspStatus.Unsupported -> "CodeTab does not know a language server for this file type. You can enter one below."
    is LspStatus.Missing -> "No language server for this file type was found on the server. Word completion is used until one is installed."
    is LspStatus.Starting -> "Starting ${status.name}…"
    is LspStatus.Ready -> "${status.name} is running" + (status.progress?.let { " · $it" } ?: ".")
    is LspStatus.Failed -> "${status.name} could not run."
}
