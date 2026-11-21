package app.jangkau.editor

import android.content.Context
import android.graphics.Typeface
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import org.eclipse.tm4e.core.registry.IThemeSource

object EditorSupport {
    private const val THEME = "textmate/jangkau-dark.json"
    @Volatile private var ready = false

    @Synchronized
    fun init(context: Context) {
        if (ready) return
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(context.applicationContext.assets))
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
        val source = IThemeSource.fromInputStream(
            FileProviderRegistry.getInstance().tryGetInputStream(THEME), THEME, null,
        )
        ThemeRegistry.getInstance().loadTheme(ThemeModel(source, "jangkau-dark").apply { isDark = true })
        ThemeRegistry.getInstance().setTheme("jangkau-dark")
        ready = true
    }

    fun create(
        context: Context,
        fileName: String,
        text: String,
        readOnly: Boolean,
        textSizeSp: Float,
        typeface: Typeface,
        onChanged: () -> Unit,
        onCursor: (line: Int, col: Int) -> Unit,
    ): CodeEditor {
        init(context)
        val lang = Languages.forName(fileName)
        return CodeEditor(context).apply {
            typefaceText = typeface
            typefaceLineNumber = typeface
            isLigatureEnabled = false
            setTextSize(textSizeSp)
            colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
            setEditorLanguage(
                lang.scope?.let { runCatching { TextMateLanguage.create(it, !readOnly) }.getOrNull() } ?: EmptyLanguage()
            )
            tabWidth = 4
            isWordwrap = false
            setPinLineNumber(true)
            isEditable = !readOnly
            setText(text)
            subscribeAlways(ContentChangeEvent::class.java) { e ->
                if (e.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) onChanged()
            }
            subscribeAlways(SelectionChangeEvent::class.java) { e -> onCursor(e.left.line + 1, e.left.column + 1) }
        }
    }
}
