package app.jangkau.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import app.jangkau.R

object Ide {
    val bg = Color(0xFF0E0E0E)
    val card = Color(0xFF181818)
    val cardRaised = Color(0xFF202020)
    val activityBar = card
    val sideBar = card
    val editor = card
    val panel = card
    val tabInactive = Color.Transparent
    val tabActive = Color(0xFF2A2A2A)
    val field = Color(0xFF222222)
    val border = Color(0xFF2A2A2A)
    val hover = Color(0xFF232323)
    val selection = Color(0xFF2A2A2A)
    val pill = Color(0xFFF2F2F2)
    val onPill = Color(0xFF111111)
    val text = Color(0xFFE4E4E4)
    val textDim = Color(0xFF8C8C8C)
    val textBright = Color(0xFFFFFFFF)
    val accent = Color(0xFF4FA3FF)
    val statusBar = bg
    val statusBarOffline = Color(0xFF5A2323)
    val added = Color(0xFF73C991)
    val modified = Color(0xFFE2C08D)
    val deleted = Color(0xFFE06C5B)
    val untracked = Color(0xFF73C991)
    val conflict = Color(0xFFE4676B)
    val folder = Color(0xFFBDBDBD)
    val python = Color(0xFF6CA8E0)
    val shell = Color(0xFF8FD16A)
    val data = Color(0xFFD7CB6A)
    val media = Color(0xFFC792EA)
    val warning = Color(0xFFE5B95C)

    val cardShape = RoundedCornerShape(18.dp)
    val itemShape = RoundedCornerShape(10.dp)
    val pillShape = RoundedCornerShape(50)

    val ui = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
        Font(R.font.inter_bold, FontWeight.Bold),
    )
    val mono = FontFamily(
        Font(R.font.cascadia_code, FontWeight.Normal),
        Font(R.font.cascadia_code_bold, FontWeight.Bold),
    )

    val label = TextStyle(fontFamily = ui, fontSize = 13.sp, color = text)
    val small = TextStyle(fontFamily = ui, fontSize = 12.sp, color = text)
    val section = TextStyle(fontFamily = ui, fontSize = 11.sp, color = textDim, letterSpacing = 0.5.sp, fontWeight = FontWeight.Medium)
    val title = TextStyle(fontFamily = ui, fontSize = 19.sp, color = textBright, fontWeight = FontWeight.Bold)
    val hero = TextStyle(fontFamily = ui, fontSize = 40.sp, color = textBright, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
}

enum class MonoFont(val label: String, val regular: Int?, val bold: Int?) {
    Cascadia("Cascadia Code", R.font.cascadia_code, R.font.cascadia_code_bold),
    JetBrains("JetBrains Mono", R.font.jetbrains_mono, R.font.jetbrains_mono_bold),
    System("Droid Sans Mono (system)", null, null);

    fun typeface(ctx: Context, bold: Boolean = false): Typeface {
        val id = if (bold) this.bold else regular
        val tf = id?.let { runCatching { ResourcesCompat.getFont(ctx, it) }.getOrNull() }
        return tf ?: if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
    }
}

class UiPrefs(private val sp: SharedPreferences? = null) {
    private val fontState = mutableStateOf(
        sp?.getString("font", null)?.let { n -> MonoFont.entries.firstOrNull { it.name == n } } ?: MonoFont.Cascadia
    )
    private val hiddenState = mutableStateOf(sp?.getBoolean("showHidden", false) ?: false)
    private val editorSize = mutableFloatStateOf(sp?.getFloat("editorSp", 14f) ?: 14f)
    private val termSize = mutableFloatStateOf(sp?.getFloat("termSp", 13f) ?: 13f)
    private val keepState = mutableStateOf(sp?.getBoolean("keepSessions", true) ?: true)
    private val echoState = mutableStateOf(sp?.getBoolean("predictiveEcho", true) ?: true)
    private val lspState = mutableStateOf(sp?.getBoolean("languageServers", true) ?: true)
    private val customServers = androidx.compose.runtime.mutableStateMapOf<String, String>().apply {
        sp?.all?.forEach { (k, v) -> if (k.startsWith("lsp.") && v is String) put(k.removePrefix("lsp."), v) }
    }

    var font: MonoFont
        get() = fontState.value
        set(v) { fontState.value = v; sp?.edit()?.putString("font", v.name)?.apply() }
    var showHidden: Boolean
        get() = hiddenState.value
        set(v) { hiddenState.value = v; sp?.edit()?.putBoolean("showHidden", v)?.apply() }
    var editorTextSp: Float
        get() = editorSize.floatValue
        set(v) { editorSize.floatValue = v.coerceIn(9f, 28f); sp?.edit()?.putFloat("editorSp", editorSize.floatValue)?.apply() }
    var terminalTextSp: Float
        get() = termSize.floatValue
        set(v) { termSize.floatValue = v.coerceIn(8f, 24f); sp?.edit()?.putFloat("termSp", termSize.floatValue)?.apply() }
    var keepSessions: Boolean
        get() = keepState.value
        set(v) { keepState.value = v; sp?.edit()?.putBoolean("keepSessions", v)?.apply() }
    var predictiveEcho: Boolean
        get() = echoState.value
        set(v) { echoState.value = v; sp?.edit()?.putBoolean("predictiveEcho", v)?.apply() }
    var languageServers: Boolean
        get() = lspState.value
        set(v) { lspState.value = v; sp?.edit()?.putBoolean("languageServers", v)?.apply() }

    fun customServer(lspId: String): String? = customServers[lspId]

    fun setCustomServer(lspId: String, command: String?) {
        val c = command?.trim().orEmpty()
        if (c.isEmpty()) { customServers.remove(lspId); sp?.edit()?.remove("lsp.$lspId")?.apply() }
        else { customServers[lspId] = c; sp?.edit()?.putString("lsp.$lspId", c)?.apply() }
    }
}

@Composable
fun JangkauTheme(content: @Composable () -> Unit) {
    val base = Typography()
    fun TextStyle.ui() = copy(fontFamily = Ide.ui)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ide.pill,
            onPrimary = Ide.onPill,
            secondary = Ide.textDim,
            background = Ide.bg,
            surface = Ide.card,
            surfaceVariant = Ide.cardRaised,
            surfaceContainerHigh = Color(0xFF1E1E1E),
            surfaceContainer = Ide.card,
            surfaceContainerLow = Ide.card,
            onSurface = Ide.text,
            onSurfaceVariant = Ide.textDim,
            outline = Color(0xFF3A3A3A),
            outlineVariant = Ide.border,
            error = Ide.deleted,
        ),
        typography = Typography(
            headlineSmall = base.headlineSmall.ui().copy(fontWeight = FontWeight.Bold),
            titleLarge = base.titleLarge.ui().copy(fontWeight = FontWeight.Bold),
            titleMedium = base.titleMedium.ui(),
            bodyLarge = base.bodyLarge.ui(),
            bodyMedium = base.bodyMedium.ui(),
            bodySmall = base.bodySmall.ui(),
            labelLarge = base.labelLarge.ui().copy(fontWeight = FontWeight.SemiBold),
            labelMedium = base.labelMedium.ui(),
            labelSmall = base.labelSmall.ui(),
        ),
        shapes = Shapes(extraLarge = RoundedCornerShape(24.dp), large = RoundedCornerShape(18.dp), medium = RoundedCornerShape(12.dp)),
        content = content,
    )
}
