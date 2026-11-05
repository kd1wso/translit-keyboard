package com.example.translit

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Панель над клавиатурой: настройки, перевод и до 4 подсказок. */
@SuppressLint("ViewConstructor")
class SuggestionStrip(
    context: Context,
    private val onSettings: () -> Unit,
    private val onTranslate: () -> Unit,
    private val onPick: (Int) -> Unit
) : LinearLayout(context) {

    private val settingsBtn = icon(R.drawable.ic_settings, "Настройки") { onSettings() }
    private val translateBtn = icon(R.drawable.ic_translate, "Перевод") { onTranslate() }
    private val chipsBox = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val chipViews = List(4) { i -> chip(i) }

    private var pal: KbSettings.Palette = KbSettings.THEMES[0].palette
    private var fontScale = 1f
    private var chips: List<InputEngine.Chip> = emptyList()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val h = dp(4f).toInt()
        setPadding(h, 0, h, 0)
        addView(settingsBtn, LayoutParams(dp(40f).toInt(), dp(36f).toInt()))
        addView(translateBtn, LayoutParams(dp(40f).toInt(), dp(36f).toInt()))
        addView(chipsBox, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        for (v in chipViews) {
            chipsBox.addView(v, LayoutParams(0, dp(34f).toInt(), 1f).apply {
                marginStart = dp(2f).toInt()
                marginEnd = dp(2f).toInt()
            })
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun icon(res: Int, desc: String, click: () -> Unit) = ImageView(context).apply {
        setImageDrawable(ContextCompat.getDrawable(context, res)?.mutate())
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = desc
        isClickable = true
        setOnClickListener { click() }
    }

    private fun chip(i: Int) = TextView(context).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        val p = dp(6f).toInt()
        setPadding(p, 0, p, 0)
        isClickable = true
        setOnClickListener { onPick(i) }
        visibility = View.GONE
    }

    private var translateActive = false

    fun applyTheme(p: KbSettings.Palette, scale: Float) {
        pal = p
        fontScale = scale
        setBackgroundColor(p.barBg)
        (settingsBtn.drawable)?.setTint(p.dimText)
        (translateBtn.drawable)?.setTint(if (translateActive) p.accent else p.dimText)
        render()
    }

    /** [translateActive] — панель перевода открыта (значок цветом акцента). */
    fun setChips(list: List<InputEngine.Chip>, showTranslate: Boolean, translateActive: Boolean = false) {
        translateBtn.visibility = if (showTranslate) View.VISIBLE else View.GONE
        if (translateActive != this.translateActive) {
            this.translateActive = translateActive
            translateBtn.drawable?.setTint(if (translateActive) pal.accent else pal.dimText)
        }
        if (list == chips) return
        chips = list
        render()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) post { render() }
    }

    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun render() {
        val shown = chips.take(chipViews.size)
        val n = shown.size
        // выделена подсказка, которую вставит пробел; раскладка и действия — цветом акцента
        val bold = shown.map { it.auto || it.kind == InputEngine.ChipKind.ACTION || it.kind == InputEngine.ChipKind.LAYOUT }
        val sizes = shown.map { (if (it.kind == InputEngine.ChipKind.ACTION) 13f else 15f) * fontScale }
        val widths = shown.mapIndexed { i, c ->
            measurePaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizes[i], resources.displayMetrics)
            measurePaint.typeface = if (bold[i]) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            measurePaint.measureText(c.label)
        }
        // подсказки делят ширину поровну; если длинное слово не влезает — по длине и чуть мельче
        val avail = (if (chipsBox.width > 0) chipsBox.width else resources.displayMetrics.widthPixels - dp(96f).toInt()).toFloat()
        val extra = dp(12f) + dp(4f) + dp(2f)
        val equal = n == 0 || widths.all { it + extra <= avail / n }
        var scale = 1f
        if (!equal) {
            val room = avail - extra * n
            val sum = widths.sum()
            if (sum > room && sum > 0f) scale = maxOf(0.78f, room / sum)
        }
        for ((i, v) in chipViews.withIndex()) {
            val c = shown.getOrNull(i)
            if (c == null) {
                v.visibility = View.GONE
                continue
            }
            v.visibility = View.VISIBLE
            v.text = c.label
            val action = c.kind == InputEngine.ChipKind.ACTION
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizes[i] * scale)
            (v.layoutParams as? LayoutParams)?.let {
                val wt = if (equal) 1f else widths[i] * scale + extra
                if (it.weight != wt) {
                    it.weight = wt
                    v.layoutParams = it
                }
            }
            val highlight = c.auto
            val accented = !highlight && (action || c.kind == InputEngine.ChipKind.LAYOUT)
            v.setTypeface(null, if (bold[i]) Typeface.BOLD else Typeface.NORMAL)
            v.setTextColor(
                when {
                    highlight -> Color.WHITE
                    accented -> pal.accent
                    c.kind == InputEngine.ChipKind.TYPED -> pal.dimText
                    else -> pal.text
                }
            )
            v.background = GradientDrawable().apply {
                cornerRadius = dp(17f)
                if (highlight) setColor(pal.accent) else {
                    setColor(pal.funcTop)
                    setStroke(dp(1f).toInt(), if (accented) pal.accent else pal.funcStroke)
                }
            }
            v.contentDescription = c.label
        }
    }
}
