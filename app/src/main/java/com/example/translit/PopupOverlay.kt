package com.example.translit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View

/**
 * Прозрачный слой поверх клавиатуры: пузырь нажатой клавиши и панель вариантов
 * (долгое нажатие). Касания не перехватывает.
 */
class PopupOverlay(context: Context) : View(context) {

    class Bubble(val rect: RectF, val text: String)

    class Panel(val variants: List<String>, val labels: List<String>, val rect: RectF, val cellW: Float, val pad: Float) {
        var selected = 0
        fun select(x: Float) {
            selected = ((x - rect.left - pad) / cellW).toInt().coerceIn(0, variants.size - 1)
        }
    }

    var palette: KbSettings.Palette? = null
    var fontScale = 1f

    /** Положение KeyboardView внутри этого слоя (координаты пузыря и панели — в системе клавиатуры). */
    var originX = 0f
    var originY = 0f

    var bubble: Bubble? = null
        set(v) { field = v; invalidate() }
    var panel: Panel? = null
        set(v) { field = v; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val tmp = RectF()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    fun clear() {
        bubble = null
        panel = null
    }

    /**
     * Слой не должен влиять на высоту клавиатуры: окно IME меряет вид «не больше экрана»,
     * и обычный View занял бы весь экран — клавиатура уползала бы наверх и закрывала чат.
     * Размер слоя задаёт FrameLayout (MATCH_PARENT) по высоте самой клавиатуры.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else 0
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(w, h)
    }

    override fun onDraw(c: Canvas) {
        val pal = palette ?: return
        val b = bubble
        val p = panel
        if (b == null && p == null) return
        c.save()
        c.translate(originX, originY)
        val r = dp(10f)
        if (b != null) {
            tmp.set(b.rect)
            tmp.offset(0f, dp(1.5f))
            fill.color = 0x40000000
            c.drawRoundRect(tmp, r, r, fill)
            fill.color = pal.bubbleBg
            c.drawRoundRect(b.rect, r, r, fill)
            text.color = pal.text
            text.typeface = Typeface.DEFAULT
            var size = sp(if (b.text.length <= 2) 26f else 20f) * fontScale
            text.textSize = size
            val maxW = b.rect.width() - dp(8f)
            val w = text.measureText(b.text)
            if (w > maxW && w > 0) {
                size *= maxW / w
                text.textSize = size
            }
            val cy = b.rect.centerY() - (text.descent() + text.ascent()) / 2
            c.drawText(b.text, b.rect.centerX(), cy, text)
        }
        if (p != null) {
            tmp.set(p.rect)
            tmp.offset(0f, dp(2f))
            fill.color = 0x50000000
            c.drawRoundRect(tmp, r, r, fill)
            fill.color = pal.keyTop
            c.drawRoundRect(p.rect, r, r, fill)
            stroke.color = pal.accent
            stroke.strokeWidth = dp(1.5f)
            c.drawRoundRect(p.rect, r, r, stroke)
            text.textSize = sp(20f) * fontScale
            for ((i, label) in p.labels.withIndex()) {
                val left = p.rect.left + p.pad + i * p.cellW
                tmp.set(left + dp(1f), p.rect.top + dp(4f), left + p.cellW - dp(1f), p.rect.bottom - dp(4f))
                if (i == p.selected) {
                    fill.color = pal.accent
                    c.drawRoundRect(tmp, dp(7f), dp(7f), fill)
                }
                text.color = if (i == p.selected) Color.WHITE else pal.text
                var size = sp(if (label.length <= 2) 20f else 15f) * fontScale
                text.textSize = size
                val w = text.measureText(label)
                val maxW = p.cellW - dp(4f)
                if (w > maxW && w > 0) {
                    size *= maxW / w
                    text.textSize = size
                }
                val cy = tmp.centerY() - (text.descent() + text.ascent()) / 2
                c.drawText(label, tmp.centerX(), cy, text)
            }
        }
        c.restore()
    }
}
