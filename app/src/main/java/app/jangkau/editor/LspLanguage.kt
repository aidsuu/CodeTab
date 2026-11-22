package app.jangkau.editor

import android.os.Bundle
import app.jangkau.core.LspCompletion
import app.jangkau.core.LspCompletionItem
import app.jangkau.core.LspDiagnostic
import app.jangkau.core.LspTextEdit
import app.jangkau.core.lspSnippetToText
import app.jangkau.lsp.LspDocument
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.completion.CompletionHelper
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionIconDrawer
import io.github.rosemoe.sora.lang.completion.snippet.parser.CodeSnippetParser
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.CodeEditor

/** Adds language server completions to a base language, falling back to its word completion. */
class LspLanguage(private val base: Language, private val doc: LspDocument) : Language by base {

    override fun requireAutoComplete(content: ContentReference, position: CharPosition, publisher: CompletionPublisher, extraArguments: Bundle) {
        val items = lspItems(content, position)
        if (items.isNullOrEmpty()) {
            base.requireAutoComplete(content, position, publisher, extraArguments)
            return
        }
        publisher.setComparator(compareBy<CompletionItem> { it.sortText })
        publisher.addItems(items)
    }

    private fun lspItems(content: ContentReference, position: CharPosition): List<CompletionItem>? {
        if (!doc.ready) return null
        val prefix = CompletionHelper.computePrefix(content, position) { Character.isJavaIdentifierPart(it) || it == '$' }
        val line = position.line
        val start = position.column - prefix.length
        val before = if (start > 0) content.charAt(line, start - 1).toString() else ""
        val trigger = before.takeIf { it.isNotBlank() && prefix.isEmpty() && doc.triggerCharacters.any { t -> t.endsWith(it) } }
        if (prefix.isEmpty() && trigger == null) return null
        val cached = doc.cache?.takeIf { it.line == line && it.start == start && prefix.startsWith(it.prefix) && !it.result.incomplete }
        val result = cached?.result ?: doc.completion(line, position.column, trigger) { content.reference.toString() }
            ?.also { doc.cache = if (it.incomplete) null else app.jangkau.lsp.CompletionCache(line, start, prefix, it) }
            ?: return null
        CompletionHelper.checkCancelled()
        return rank(result, prefix).map { (item, rank) -> LspItem(item, prefix.length, rank) }
    }

    companion object {
        private const val MAX_ITEMS = 150
        private val lspKinds = listOf(
            "Text", "Method", "Function", "Constructor", "Field", "Variable", "Class", "Interface", "Module", "Property",
            "Unit", "Value", "Enum", "Keyword", "Snippet", "Color", "File", "Reference", "Folder", "EnumMember",
            "Constant", "Struct", "Event", "Operator", "TypeParameter",
        )

        fun kind(lsp: Int): CompletionItemKind =
            lspKinds.getOrNull(lsp - 1)?.let { n -> runCatching { CompletionItemKind.valueOf(n) }.getOrNull() } ?: CompletionItemKind.Text

        /** Keeps items that match the typed prefix, best matches first. */
        fun rank(result: LspCompletion, prefix: String): List<Pair<LspCompletionItem, Int>> {
            val scored = result.items.mapNotNull { item ->
                val key = item.filterText.ifEmpty { item.label }
                val score = when {
                    prefix.isEmpty() -> 1
                    key.startsWith(prefix) -> 0
                    key.startsWith(prefix, ignoreCase = true) -> 1
                    subsequence(prefix, key) -> 2
                    else -> return@mapNotNull null
                }
                Triple(item, score, key)
            }
            return scored.sortedWith(compareBy<Triple<LspCompletionItem, Int, String>> { it.second }.thenBy { it.first.sortText }.thenBy { it.third.length })
                .take(MAX_ITEMS)
                .mapIndexed { i, t -> t.first to i }
        }

        private fun subsequence(needle: String, hay: String): Boolean {
            var i = 0
            for (c in hay) if (i < needle.length && c.equals(needle[i], ignoreCase = true)) i++
            return i == needle.length
        }

        /** Converts diagnostics to editor regions, clamped to the current text. */
        fun regions(text: Content, diagnostics: List<LspDiagnostic>): DiagnosticsContainer {
            val container = DiagnosticsContainer()
            fun index(line: Int, col: Int): Int {
                val l = line.coerceIn(0, text.lineCount - 1)
                return text.getCharIndex(l, col.coerceIn(0, text.getColumnCount(l)))
            }
            diagnostics.forEachIndexed { i, d ->
                val start = index(d.range.start.line.toInt(), d.range.start.character.toInt())
                var end = index(d.range.end.line.toInt(), d.range.end.character.toInt())
                if (end <= start) end = (start + 1).coerceAtMost(text.length)
                val severity = when (d.severity.toInt()) {
                    1 -> DiagnosticRegion.SEVERITY_ERROR
                    2 -> DiagnosticRegion.SEVERITY_WARNING
                    else -> DiagnosticRegion.SEVERITY_TYPO
                }
                val source = listOfNotNull(d.source, d.code).joinToString(" ")
                container.addDiagnostic(DiagnosticRegion(start, end, severity, i.toLong(), DiagnosticDetail(d.message, source.ifEmpty { null }, null, null)))
            }
            return container
        }
    }
}

class LspItem(val item: LspCompletionItem, prefixLength: Int, rank: Int) :
    CompletionItem(item.label, item.detail?.lineSequence()?.firstOrNull()?.take(60)) {

    init {
        this.prefixLength = prefixLength
        sortText = "%05d".format(rank)
        val k = LspLanguage.kind(item.kind.toInt())
        kind(k)
        icon = runCatching { SimpleCompletionIconDrawer.draw(k) }.getOrNull()
    }

    override fun performCompletion(editor: CodeEditor, text: Content, line: Int, column: Int) {
        val range = item.editRange?.takeIf { it.start.line.toInt() == line && it.start.character.toInt() <= column }
        val start = (range?.start?.character?.toInt() ?: (column - prefixLength)).coerceIn(0, column)
        val (after, before) = item.additionalEdits.partition {
            it.range.start.line.toInt() > line || (it.range.start.line.toInt() == line && it.range.start.character.toInt() >= column)
        }
        text.beginBatchEdit()
        try {
            after.sortedByDescending { pos(it) }.forEach { apply(text, it) }
            text.delete(line, start, line, column)
            if (item.snippet) {
                val ok = runCatching {
                    editor.snippetController.startSnippet(text.getCharIndex(line, start), CodeSnippetParser.parse(item.insertText), "")
                }.isSuccess
                if (!ok) text.insert(line, start, lspSnippetToText(item.insertText))
            } else {
                text.insert(line, start, item.insertText)
            }
            before.sortedByDescending { pos(it) }.forEach { apply(text, it) }
        } finally {
            text.endBatchEdit()
        }
    }

    private fun pos(e: LspTextEdit) = e.range.start.line.toLong() * 1_000_000 + e.range.start.character.toLong()

    private fun apply(text: Content, e: LspTextEdit) {
        val last = text.lineCount - 1
        val sl = e.range.start.line.toInt().coerceIn(0, last)
        val el = e.range.end.line.toInt().coerceIn(sl, last)
        val sc = e.range.start.character.toInt().coerceIn(0, text.getColumnCount(sl))
        val ec = e.range.end.character.toInt().coerceIn(0, text.getColumnCount(el))
        if (sl != el || sc != ec) text.delete(sl, sc, el, ec)
        if (e.newText.isNotEmpty()) text.insert(sl, sc, e.newText)
    }

    override fun performCompletion(editor: CodeEditor, text: Content, position: CharPosition) =
        performCompletion(editor, text, position.line, position.column)
}
