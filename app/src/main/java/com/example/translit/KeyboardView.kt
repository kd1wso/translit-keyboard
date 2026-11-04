package com.example.translit

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Клавиатура, нарисованная на Canvas: без «мёртвых зон» между клавишами, с мультитачем
 * (быстрый набор двумя пальцами), выбором вариантов скольжением, повтором ⌫,
 * свайпом по пробелу (курсор) и влево по ⌫ (удаление слов).
 */
class KeyboardView(context: Context) : View(context) {

    enum class Type { CHAR, SHIFT, BACKSPACE, SPACE, ENTER, MODE, EMOJI, GLOBE }

    class Key(
        val type: Type,
        val label: String = "",
        val text: String = label,
        /** Ширина в «единицах» (обычная клавиша = 1); 0 — занять остаток ряда. */
        val width: Float = 1f,
        val longPress: List<String> = emptyList(),
        val icon: Int = 0,
        val func: Boolean = false,
        /** Для MODE: номер страницы (Page.ordinal). */
        val target: Int = 0,
        /** Маленькая подпись в углу (первый вариант долгого нажатия). */
        val hint: String? = null,
        /** Подпись крупнее/мельче обычной. */
        val textScale: Float = 1f
    ) {
        var x = 0f
        var y = 0f
        var w = 0f
        var h = 0f
        var hitL = 0f
        var hitR = 0f
        internal var shader: Shader? = null
    }

    class Row(val keys: List<Key>, val fill: Boolean = false)

    interface Listener {
        fun onKeyPress(key: Key)
        fun onKey(key: Key)
        fun onVariant(key: Key, text: String)
        fun onShiftDown()
        fun onShiftLongPress()
        fun onBackspaceRepeat(count: Int)
        fun onBackspaceSwipeStart(): Boolean
        fun onBackspaceSwipe(steps: Int)
        fun onBackspaceSwipeEnd()
        fun onCursorSwipe(steps: Int)
        fun onSystemLongPress(key: Key)
        fun previewText(key: Key): String?
    }

    var listener: Listener? = null
    var popup: PopupOverlay? = null

    var palette: KbSettings.Palette = KbSettings.THEMES[0].palette
        set(v) {
            field = v
            for (r in rows) for (k in r.keys) k.shader = null
            invalidate()
        }
    var fontScale = 1f
        set(v) { field = v; invalidate() }
    var rowHeight = dp(53f).toInt()
        set(v) { field = v; requestLayout(); layoutKeys(); invalidate() }
    var shiftState = InputEngine.Shift.OFF
        set(v) { if (field != v) { field = v; invalidate() } }
    var spaceLabel = ""
        set(v) { field = v; invalidate() }

    var rows: List<Row> = emptyList()
        private set
    private val handler = Handler(Looper.getMainLooper())

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val rect = RectF()
    private val icons = HashMap<Int, Drawable>()

    private val gapX = dp(2.5f)
    private val gapY = dp(3.5f)
    private val radius = dp(9f)
    private val iconSize = dp(22f).toInt()

    private val longPressMs = 380L
    private val repeatStartMs = 400L
    private val spaceSlop = dp(14f)
    private val cursorStep = dp(9f)
    private val backSlop = dp(24f)
    private val backStep = dp(36f)

    private val pressed = HashSet<Key>()
    private var bubbleKey: Key? = null

    private enum class Mode { NORMAL, PANEL, CURSOR, BACK_SWIPE, REPEAT }

    private inner class Ptr(var key: Key, val downX: Float, val downY: Float) {
        var consumed = false
        var mode = Mode.NORMAL
        var anchorX = 0f
        var steps = 0
        var repeatCount = 0
        var panel: PopupOverlay.Panel? = null
        var timer: Runnable? = null
    }

    private val ptrs = HashMap<Int, Ptr>()

    init {
        isHapticFeedbackEnabled = true
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    fun setRows(newRows: List<Row>) {
        cancelTouches()
        rows = newRows
        requestLayout()
        layoutKeys()
        invalidate()
    }

    val upperCase: Boolean get() = shiftState != InputEngine.Shift.OFF

    // ================================================================ разметка

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = paddingTop + paddingBottom + rowHeight * rows.size
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutKeys()
    }

    private fun layoutKeys() {
        val avail = (width - paddingLeft - paddingRight).toFloat()
        if (avail <= 0f || rows.isEmpty()) return
        val minFlex = 1.2f
        var units = rows.filter { !it.fill }.maxOfOrNull { r -> r.keys.sumOf { it.width.toDouble() } }?.toFloat() ?: 0f
        if (units <= 0f) {
            units = rows.maxOf { r -> r.keys.sumOf { (if (it.width > 0f) it.width else minFlex).toDouble() } }.toFloat()
        }
        val unit = avail / units
        var y = paddingTop.toFloat()
        for (row in rows) {
            val fixed = row.keys.sumOf { it.width.toDouble() }.toFloat()
            val flexCount = row.keys.count { it.width <= 0f }
            val flexW = if (flexCount > 0) max((units - fixed) / flexCount, minFlex) else 0f
            val total = fixed + flexW * flexCount
            val scale = if (row.fill || total > units) units / total else 1f
            val rowW = total * scale * unit
            var x = paddingLeft + (avail - rowW) / 2f
            for ((i, k) in row.keys.withIndex()) {
                val kw = (if (k.width <= 0f) flexW else k.width) * scale * unit
                k.x = x
                k.y = y
                k.w = kw
                k.h = rowHeight.toFloat()
                k.hitL = if (i == 0) 0f else x
                k.hitR = if (i == row.keys.size - 1) width.toFloat() else x + kw
                k.shader = null
                x += kw
            }
            y += rowHeight
        }
    }

    private fun keyAt(x: Float, y: Float): Key? {
        if (rows.isEmpty()) return null
        val r = ((y - paddingTop) / rowHeight).toInt().coerceIn(0, rows.size - 1)
        val row = rows[r]
        for (k in row.keys) if (x >= k.hitL && x < k.hitR) return k
        return row.keys.minByOrNull { abs(it.x + it.w / 2 - x) }
    }

    // ================================================================ рисование

    private fun icon(res: Int): Drawable? =
        icons[res] ?: ContextCompat.getDrawable(context, res)?.mutate()?.also { icons[res] = it }

    private fun displayLabel(k: Key): String {
        val l = k.label
        return if (k.type == Type.CHAR && upperCase && l.length == 1 && l[0].isLetter()) l.uppercase() else l
    }

    private fun darker(c: Int, f: Float): Int = Color.argb(
        Color.alpha(c), (Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt()
    )

    override fun onDraw(c: Canvas) {
        val pal = palette
        c.drawColor(pal.bg)
        for (row in rows) for (k in row.keys) drawKey(c, k, pal)
    }

    private fun drawKey(c: Canvas, k: Key, pal: KbSettings.Palette) {
        rect.set(k.x + gapX, k.y + gapY, k.x + k.w - gapX, k.y + k.h - gapY)
        if (rect.width() <= 0f) return
        val isPressed = k in pressed
        val func = k.func
        fill.shader = null
        when {
            k.type == Type.ENTER -> fill.color = if (isPressed) darker(pal.accent, 0.8f) else pal.accent
            isPressed -> fill.color = if (func) pal.pressedFunc else pal.pressedKey
            else -> {
                val sh = k.shader ?: LinearGradient(
                    0f, rect.top, 0f, rect.bottom,
                    if (func) pal.funcTop else pal.keyTop,
                    if (func) pal.funcBottom else pal.keyBottom,
                    Shader.TileMode.CLAMP
                ).also { k.shader = it }
                fill.shader = sh
            }
        }
        c.drawRoundRect(rect, radius, radius, fill)
        fill.shader = null
        if (k.type != Type.ENTER) {
            stroke.color = if (func) pal.funcStroke else pal.keyStroke
            stroke.strokeWidth = dp(1f)
            c.drawRoundRect(rect, radius, radius, stroke)
        }

        val cx = rect.centerX()
        val cy = rect.centerY()
        val iconRes = when (k.type) {
            Type.SHIFT -> if (shiftState == InputEngine.Shift.LOCKED) R.drawable.ic_capslock else R.drawable.ic_shift
            else -> k.icon
        }
        if (iconRes != 0) {
            val d = icon(iconRes)
            if (d != null) {
                val tint = when {
                    k.type == Type.ENTER -> Color.WHITE
                    k.type == Type.SHIFT && shiftState != InputEngine.Shift.OFF -> pal.accent
                    else -> pal.text
                }
                d.setTint(tint)
                val s = iconSize
                d.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
                d.draw(c)
            }
            if (k.type == Type.SHIFT && shiftState == InputEngine.Shift.LOCKED) {
                fill.color = pal.accent
                c.drawRoundRect(cx - dp(7f), rect.bottom - dp(7f), cx + dp(7f), rect.bottom - dp(5f), dp(1f), dp(1f), fill)
            }
            return
        }

        val label = if (k.type == Type.SPACE) k.label.ifEmpty { spaceLabel } else displayLabel(k)
        if (label.isEmpty()) return
        val base = when {
            k.type == Type.SPACE -> 12.5f
            k.type != Type.CHAR -> 14f
            label.length == 1 && label[0].isLetter() -> 21f
            label.codePointCount(0, label.length) <= 2 && !label[0].isLetterOrDigit() && label[0].code > 0x2000 -> 22f
            label.length <= 2 -> 19f
            else -> 15f
        }
        var size = sp(base) * fontScale * k.textScale
        textPaint.textSize = size
        textPaint.typeface = Typeface.DEFAULT
        textPaint.color = if (k.type == Type.SPACE) pal.dimText else pal.text
        val maxW = rect.width() - dp(6f)
        val tw = textPaint.measureText(label)
        if (tw > maxW && tw > 0f) {
            size *= maxW / tw
            textPaint.textSize = size
        }
        c.drawText(label, cx, cy - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)

        val hint = k.hint
        if (hint != null && rect.width() > dp(18f)) {
            hintPaint.textSize = sp(10f) * fontScale
            hintPaint.color = pal.dimText
            val h = if (upperCase && hint.length == 1 && hint[0].isLetter()) hint.uppercase() else hint
            c.drawText(h, rect.right - dp(4f), rect.top + dp(3f) - hintPaint.ascent(), hintPaint)
        }
    }

    // ================================================================ касания

    private fun press(k: Key) {
        pressed.add(k)
        invalidate()
    }

    private fun release(k: Key) {
        pressed.remove(k)
        invalidate()
    }

    private fun schedule(p: Ptr, delay: Long, block: () -> Unit) {
        p.timer?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            p.timer = null
            if (ptrs.containsValue(p)) block()
        }
        p.timer = r
        handler.postDelayed(r, delay)
    }

    private fun cancelTimer(p: Ptr) {
        p.timer?.let { handler.removeCallbacks(it) }
        p.timer = null
    }

    fun cancelTouches() {
        for (p in ptrs.values) cancelTimer(p)
        ptrs.clear()
        pressed.clear()
        bubbleKey = null
        popup?.clear()
        invalidate()
    }

    private fun syncPopupOrigin() {
        val ov = popup ?: return
        val a = IntArray(2)
        val b = IntArray(2)
        getLocationInWindow(a)
        ov.getLocationInWindow(b)
        ov.originX = (a[0] - b[0]).toFloat()
        ov.originY = (a[1] - b[1]).toFloat()
        ov.palette = palette
        ov.fontScale = fontScale
    }

    private fun showBubble(k: Key) {
        val ov = popup ?: return
        val text = listener?.previewText(k) ?: run { hideBubble(); return }
        syncPopupOrigin()
        val w = max(k.w * 1.1f, dp(46f))
        val h = k.h * 1.05f + dp(4f)
        val bottom = k.y + gapY + k.h * 0.12f
        var top = bottom - h
        val minTop = -ov.originY
        if (top < minTop) top = minTop
        var left = k.x + k.w / 2 - w / 2
        left = left.coerceIn(0f, max(0f, width - w))
        bubbleKey = k
        ov.bubble = PopupOverlay.Bubble(RectF(left, top, left + w, top + h), text)
    }

    private fun hideBubble() {
        bubbleKey = null
        popup?.bubble = null
    }

    private fun openPanel(p: Ptr) {
        val k = p.key
        if (p.consumed || p.mode != Mode.NORMAL || k.longPress.isEmpty()) return
        val ov = popup ?: return
        syncPopupOrigin()
        val labels = k.longPress.map { if (upperCase && it.length == 1 && it[0].isLetter()) it.uppercase() else it }
        val cellW = max(k.w * 0.95f, dp(36f)).let { cw ->
            val maxCell = (width - dp(12f)) / labels.size
            min(cw, maxCell)
        }
        val pad = dp(4f)
        val pw = cellW * labels.size + pad * 2
        val ph = k.h * 0.95f
        var left = k.x + k.w / 2 - cellW / 2 - pad
        left = left.coerceIn(dp(2f), max(dp(2f), width - pw - dp(2f)))
        var top = k.y + gapY - ph - dp(2f)
        val minTop = -ov.originY
        if (top < minTop) top = minTop
        val panel = PopupOverlay.Panel(k.longPress, labels, RectF(left, top, left + pw, top + ph), cellW, pad)
        panel.select(p.downX)
        p.panel = panel
        p.mode = Mode.PANEL
        p.consumed = true
        hideBubble()
        ov.panel = panel
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun startRepeat(p: Ptr) {
        if (p.mode != Mode.NORMAL || p.consumed) return
        p.mode = Mode.REPEAT
        p.consumed = true
        repeatTick(p)
    }

    private fun repeatTick(p: Ptr) {
        p.repeatCount++
        listener?.onBackspaceRepeat(p.repeatCount)
        schedule(p, if (p.repeatCount < 20) 50L else 160L) { repeatTick(p) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                pointerDown(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val p = ptrs[e.getPointerId(i)] ?: continue
                    pointerMove(p, e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                val p = ptrs.remove(e.getPointerId(i))
                if (p != null) pointerUp(p)
            }
            MotionEvent.ACTION_CANCEL -> cancelTouches()
        }
        return true
    }

    private fun pointerDown(id: Int, x: Float, y: Float) {
        // быстрый набор: новый палец «отпускает» предыдущую букву
        for (p in ptrs.values) {
            if (!p.consumed && p.mode == Mode.NORMAL && p.key.type == Type.CHAR) {
                cancelTimer(p)
                p.consumed = true
                release(p.key)
                listener?.onKey(p.key)
            }
        }
        val key = keyAt(x, y) ?: return
        val p = Ptr(key, x, y)
        ptrs[id] = p
        press(key)
        listener?.onKeyPress(key)
        when (key.type) {
            Type.CHAR -> {
                showBubble(key)
                if (key.longPress.isNotEmpty()) schedule(p, longPressMs) { openPanel(p) }
            }
            Type.SHIFT -> {
                p.consumed = true
                listener?.onShiftDown()
                schedule(p, 500L) { listener?.onShiftLongPress() }
            }
            Type.BACKSPACE -> schedule(p, repeatStartMs) { startRepeat(p) }
            Type.SPACE, Type.GLOBE -> schedule(p, 600L) {
                if (p.mode == Mode.NORMAL && !p.consumed) {
                    p.consumed = true
                    release(p.key)
                    listener?.onSystemLongPress(p.key)
                }
            }
            else -> Unit
        }
    }

    private fun pointerMove(p: Ptr, x: Float, y: Float) {
        val key = p.key
        when (p.mode) {
            Mode.PANEL -> {
                val panel = p.panel ?: return
                val old = panel.selected
                panel.select(x)
                if (panel.selected != old) popup?.invalidate()
            }
            Mode.CURSOR -> {
                val steps = ((x - p.anchorX) / cursorStep).toInt()
                if (steps != p.steps) {
                    listener?.onCursorSwipe(steps - p.steps)
                    p.steps = steps
                }
            }
            Mode.BACK_SWIPE -> {
                val d = p.anchorX - x
                val steps = if (d < -backStep / 2) 0 else (d / backStep).toInt() + 1
                if (steps != p.steps) {
                    p.steps = max(0, steps)
                    listener?.onBackspaceSwipe(p.steps)
                }
            }
            Mode.REPEAT -> Unit
            Mode.NORMAL -> when (key.type) {
                Type.SPACE -> if (abs(x - p.downX) > spaceSlop && !p.consumed) {
                    cancelTimer(p)
                    p.mode = Mode.CURSOR
                    p.consumed = true
                    p.anchorX = x
                    p.steps = 0
                }
                Type.BACKSPACE -> if (p.downX - x > backSlop && !p.consumed) {
                    cancelTimer(p)
                    p.consumed = true
                    if (listener?.onBackspaceSwipeStart() == true) {
                        p.mode = Mode.BACK_SWIPE
                        p.anchorX = x
                        p.steps = 1
                        listener?.onBackspaceSwipe(1)
                    }
                }
                Type.CHAR -> if (!p.consumed) {
                    val k2 = keyAt(x, y)
                    if (k2 != null && k2 !== key && k2.type == Type.CHAR) {
                        cancelTimer(p)
                        release(key)
                        p.key = k2
                        press(k2)
                        showBubble(k2)
                        if (k2.longPress.isNotEmpty()) schedule(p, longPressMs) { openPanel(p) }
                    }
                }
                else -> Unit
            }
        }
    }

    private fun pointerUp(p: Ptr) {
        cancelTimer(p)
        val key = p.key
        when (p.mode) {
            Mode.PANEL -> {
                val panel = p.panel
                popup?.panel = null
                if (panel != null) listener?.onVariant(key, panel.variants[panel.selected])
            }
            Mode.BACK_SWIPE -> listener?.onBackspaceSwipeEnd()
            Mode.CURSOR, Mode.REPEAT -> Unit
            Mode.NORMAL -> if (!p.consumed) listener?.onKey(key)
        }
        release(key)
        if (bubbleKey === key) hideBubble()
    }

    override fun onDetachedFromWindow() {
        cancelTouches()
        super.onDetachedFromWindow()
    }
}
