package app.jangkau.term

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import app.jangkau.core.Mods
import app.jangkau.core.TermKey
import app.jangkau.core.TermRun
import app.jangkau.core.TermSnapshot
import app.jangkau.core.encodeKey
import app.jangkau.core.encodeText
import kotlin.math.abs
import kotlin.math.max

interface TerminalSource {
    fun snapshot(scrollOffset: Int): TermSnapshot
    fun send(bytes: ByteArray)
    fun resize(cols: Int, rows: Int)
    fun consumeMods(): Mods
}

@SuppressLint("ViewConstructor")
class TerminalView(context: Context) : View(context) {
    var source: TerminalSource? = null
        set(value) { field = value; scrollOffset = 0; requestLayout(); invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; fontFeatureSettings = "'liga' 0, 'calt' 0" }
    private var regularFace: Typeface = Typeface.MONOSPACE
    private var boldFace: Typeface? = null

    fun setFonts(regular: Typeface, bold: Typeface?) {
        if (regular == regularFace && bold == boldFace) return
        regularFace = regular
        boldFace = bold
        paint.typeface = regular
        measureCell()
        onSizeChanged(width, height, width, height)
        invalidate()
    }
    private val bgPaint = Paint()
    private var cellW = 0f
    private var cellH = 0f
    private var ascent = 0f
    private var scrollOffset = 0
    private var scrollAccum = 0f
    private var lastSnapshot: TermSnapshot? = null

    var textSizeSp = 13f
        set(value) {
            if (field == value) return
            field = value.coerceIn(8f, 28f); measureCell(); onSizeChanged(width, height, width, height); invalidate()
        }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        measureCell()
    }

    private fun measureCell() {
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSizeSp, resources.displayMetrics)
        cellW = paint.measureText("M")
        val fm = paint.fontMetrics
        ascent = -fm.ascent
        cellH = fm.descent - fm.ascent
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0 || cellW <= 0f) return
        val cols = max(2, ((w - paddingLeft - paddingRight) / cellW).toInt())
        val rows = max(2, ((h - paddingTop - paddingBottom) / cellH).toInt())
        source?.resize(cols, rows)
    }

    fun refresh() = postInvalidateOnAnimation()

    override fun onDraw(canvas: Canvas) {
        bgPaint.color = Palette.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val src = source ?: return
        val snap = src.snapshot(scrollOffset)
        lastSnapshot = snap
        scrollOffset = snap.scrollOffset.toInt()
        val x0 = paddingLeft.toFloat()
        val y0 = paddingTop.toFloat()
        snap.lines.forEachIndexed { r, row ->
            val top = y0 + r * cellH
            row.runs.forEachIndexed { i, run ->
                val next = row.runs.getOrNull(i + 1)
                drawRun(canvas, run, next, x0, top)
            }
        }
        if (snap.cursorVisible) {
            val cx = x0 + snap.cursorCol.toInt() * cellW
            val cy = y0 + snap.cursorRow.toInt() * cellH
            bgPaint.color = Palette.cursor
            if (hasFocus()) {
                bgPaint.style = Paint.Style.FILL
                bgPaint.alpha = 170
            } else {
                bgPaint.style = Paint.Style.STROKE
                bgPaint.strokeWidth = 2f
            }
            canvas.drawRect(cx, cy, cx + cellW, cy + cellH, bgPaint)
            bgPaint.style = Paint.Style.FILL
            bgPaint.alpha = 255
        }
        if (snap.scrollOffset > 0u) {
            paint.color = Palette.hint
            val label = "↑ riwayat ${snap.scrollOffset}/${snap.scrollbackAvailable}"
            canvas.drawText(label, width - paint.measureText(label) - cellW, y0 + ascent, paint)
        }
    }

    private fun drawRun(canvas: Canvas, run: TermRun, next: TermRun?, x0: Float, top: Float) {
        val len = run.text.codePointCount(0, run.text.length)
        val cells = if (next != null && len == 1) (next.col.toInt() - run.col.toInt()).coerceIn(1, 2) else len
        var fg = Palette.resolve(run.fg, foreground = true, bold = run.bold)
        var bg = Palette.resolve(run.bg, foreground = false, bold = false)
        if (run.inverse) { val t = fg; fg = bg; bg = t }
        val x = x0 + run.col.toInt() * cellW
        if (bg != Palette.background) {
            bgPaint.color = bg
            canvas.drawRect(x, top, x + cells * cellW, top + cellH, bgPaint)
        }
        paint.color = fg
        if (run.dim) paint.alpha = 150
        val bf = boldFace
        if (run.bold && bf != null) paint.typeface = bf else paint.isFakeBoldText = run.bold
        paint.textSkewX = if (run.italic) -0.2f else 0f
        paint.isUnderlineText = run.underline
        canvas.drawText(run.text, x, top + ascent, paint)
        paint.alpha = 255
        paint.isFakeBoldText = false
        paint.typeface = regularFace
        paint.textSkewX = 0f
        paint.isUnderlineText = false
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            requestFocus()
            showKeyboard()
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollAccum += dy
            val lines = (scrollAccum / cellH).toInt()
            if (lines == 0) return true
            scrollAccum -= lines * cellH
            val snap = lastSnapshot
            if (snap?.altScreen == true) {
                val key = if (lines > 0) TermKey.Down else TermKey.Up
                repeat(abs(lines)) { source?.send(encodeKey(key, NO_MODS, snap.appCursor)) }
            } else {
                scrollOffset = (scrollOffset - lines).coerceIn(0, (snap?.scrollbackAvailable ?: 0u).toInt())
                invalidate()
            }
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event) || super.onTouchEvent(event)

    fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun sendText(text: String) {
        val src = source ?: return
        scrollOffset = 0
        src.send(encodeText(text, src.consumeMods()))
    }

    fun sendKey(key: TermKey, mods: Mods = NO_MODS) {
        val src = source ?: return
        scrollOffset = 0
        val sticky = src.consumeMods()
        val m = Mods(mods.ctrl || sticky.ctrl, mods.alt || sticky.alt, mods.shift || sticky.shift)
        src.send(encodeKey(key, m, lastSnapshot?.appCursor ?: false))
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                flush()
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                flush()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (editable.isNullOrEmpty()) repeat(max(1, beforeLength)) { sendKey(TermKey.Backspace) }
                else super.deleteSurroundingText(beforeLength, afterLength)
                return true
            }

            private fun flush() {
                val e = editable ?: return
                val s = e.toString()
                if (s.isNotEmpty()) {
                    sendText(s)
                    e.clear()
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val mods = Mods(event.isCtrlPressed, event.isAltPressed, event.isShiftPressed)
        val key = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> TermKey.Up
            KeyEvent.KEYCODE_DPAD_DOWN -> TermKey.Down
            KeyEvent.KEYCODE_DPAD_LEFT -> TermKey.Left
            KeyEvent.KEYCODE_DPAD_RIGHT -> TermKey.Right
            KeyEvent.KEYCODE_MOVE_HOME -> TermKey.Home
            KeyEvent.KEYCODE_MOVE_END -> TermKey.End
            KeyEvent.KEYCODE_PAGE_UP -> TermKey.PageUp
            KeyEvent.KEYCODE_PAGE_DOWN -> TermKey.PageDown
            KeyEvent.KEYCODE_INSERT -> TermKey.Insert
            KeyEvent.KEYCODE_FORWARD_DEL -> TermKey.Delete
            KeyEvent.KEYCODE_ESCAPE -> TermKey.Escape
            KeyEvent.KEYCODE_TAB -> TermKey.Tab
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> TermKey.Enter
            KeyEvent.KEYCODE_DEL -> TermKey.Backspace
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> TermKey.F((keyCode - KeyEvent.KEYCODE_F1 + 1).toUByte())
            else -> null
        }
        if (key != null) {
            sendKey(key, mods)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        val meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        val uc = event.getUnicodeChar(meta)
        if (uc > 0) {
            val src = source ?: return true
            val sticky = src.consumeMods()
            scrollOffset = 0
            src.send(encodeText(String(Character.toChars(uc)), Mods(mods.ctrl || sticky.ctrl, mods.alt || sticky.alt, false)))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        val NO_MODS = Mods(ctrl = false, alt = false, shift = false)
    }
}

object Palette {
    const val background = 0xFF181818.toInt()
    const val foreground = 0xFFCCCCCC.toInt()
    const val cursor = 0xFFAEAFAD.toInt()
    const val hint = 0xFF6A9955.toInt()

    private val base = intArrayOf(
        0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
        0x666666, 0xF14C4C, 0x23D18B, 0xF5F543, 0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF,
    )

    private val table = IntArray(256) { i ->
        val rgb = when {
            i < 16 -> base[i]
            i < 232 -> {
                val n = i - 16
                val steps = intArrayOf(0, 95, 135, 175, 215, 255)
                (steps[n / 36] shl 16) or (steps[(n / 6) % 6] shl 8) or steps[n % 6]
            }
            else -> { val v = 8 + (i - 232) * 10; (v shl 16) or (v shl 8) or v }
        }
        0xFF000000.toInt() or rgb
    }

    fun resolve(color: Int, foreground: Boolean, bold: Boolean): Int = when {
        color < 0 -> if (foreground) this.foreground else background
        color and 0x1000000 != 0 -> 0xFF000000.toInt() or (color and 0xFFFFFF)
        bold && foreground && color < 8 -> table[color + 8]
        else -> table[color.coerceIn(0, 255)]
    }
}
