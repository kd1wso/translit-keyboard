package com.example.translit

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
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

    fun applyTheme(p: KbSettings.Palette, scale: Float) {
        pal = p
        fontScale = scale
        setBackgroundColor(p.barBg)
        (settingsBtn.drawable)?.setTint(p.dimText)
        (translateBtn.drawable)?.setTint(p.dimText)
        render()
    }

    fun setChips(list: List<InputEngine.Chip>, showTranslate: Boolean) {
        translateBtn.visibility = if (showTranslate) View.VISIBLE else View.GONE
        if (list == chips) return
        chips = list
        render()
    }

    private fun render() {
        for ((i, v) in chipViews.withIndex()) {
            val c = chips.getOrNull(i)
            if (c == null) {
                v.visibility = View.GONE
                continue
            }
            v.visibility = View.VISIBLE
            v.text = c.label
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * fontScale)
            val highlight = c.kind == InputEngine.ChipKind.CORRECTION
            v.setTypeface(null, if (highlight) Typeface.BOLD else Typeface.NORMAL)
            v.setTextColor(
                when {
                    highlight -> Color.WHITE
                    c.kind == InputEngine.ChipKind.TYPED -> pal.dimText
                    else -> pal.text
                }
            )
            v.background = GradientDrawable().apply {
                cornerRadius = dp(17f)
                if (highlight) setColor(pal.accent) else {
                    setColor(pal.funcTop)
                    setStroke(dp(1f).toInt(), pal.funcStroke)
                }
            }
            v.contentDescription = c.label
        }
    }
}
