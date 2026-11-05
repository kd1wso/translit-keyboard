package com.example.translit

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Панель перевода над клавиатурой:
 *  • «Набор» — пишете в строке панели, в поле сразу попадает перевод;
 *  • «Поле» / «Буфер» — перевод текста поля (или выделенного) и скопированного;
 *  • выбор языка перевода.
 */
@SuppressLint("ViewConstructor")
class TranslatePanel(context: Context, private val host: Host) : LinearLayout(context) {

    interface Host {
        fun onClose()
        fun onLanguageButton()
        fun onPickLanguage(code: String)
        fun onLive()
        fun onTranslateField()
        fun onTranslateClipboard()
        fun onApply()
        fun onCopy()
        fun onDownload(langs: List<String>)
    }

    enum class Mode { LIVE, RESULT, PICKER }

    companion object {
        private const val LIVE_WINDOW = 110
    }

    var mode = Mode.LIVE
        private set

    private var pal: KbSettings.Palette = KbSettings.THEMES[0].palette
    private var scale = 1f

    // --- заголовок
    private val header = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val closeBtn = button("✕") { host.onClose() }
    private val langBtn = button("") { host.onLanguageButton() }
    private val liveBtn = button("Набор") { host.onLive() }
    private val fieldBtn = button("Поле") { host.onTranslateField() }
    private val clipBtn = button("Буфер") { host.onTranslateClipboard() }

    // --- набор
    private val liveBody = LinearLayout(context).apply { orientation = VERTICAL }
    private val liveBox = TextView(context)
    private val liveStatus = TextView(context)
    private val liveDownload = button("Скачать") { host.onDownload(missing) }

    // --- результат
    private val resultBody = LinearLayout(context).apply { orientation = VERTICAL }
    private val resultSource = TextView(context)
    private val resultText = TextView(context)
    private val resultStatus = TextView(context)
    private val applyBtn = button("") { host.onApply() }
    private val copyBtn = button("Копировать") { host.onCopy() }
    private val resultDownload = button("Скачать") { host.onDownload(missing) }
    private var resultOk = false

    // --- языки
    private val pickerScroll = ScrollView(context)
    private val pickerGrid = LinearLayout(context).apply { orientation = VERTICAL }
    private var pickerCurrent = ""

    private var missing: List<String> = emptyList()
    private var liveError = false
    private var resultError = false

    init {
        orientation = VERTICAL
        val m = dp(3f)
        header.addView(closeBtn, LayoutParams(dp(36f), dp(32f)).apply { setMargins(m, m, m, m) })
        header.addView(langBtn, LayoutParams(0, dp(32f), 1f).apply { setMargins(m, m, m, m) })
        for (b in listOf(liveBtn, fieldBtn, clipBtn)) {
            header.addView(b, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32f)).apply { setMargins(m, m, m, m) })
        }
        langBtn.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        liveBox.apply {
            minLines = 2
            maxLines = 3
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            val p = dp(8f)
            setPadding(p, dp(4f), p, dp(4f))
        }
        liveBody.addView(liveBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(6f), 0, dp(6f), 0)
        })
        val liveRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        liveStatus.maxLines = 2
        liveRow.addView(liveStatus, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        liveRow.addView(liveDownload, LayoutParams(LayoutParams.WRAP_CONTENT, dp(28f)).apply { marginStart = dp(4f) })
        liveBody.addView(liveRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(10f), dp(2f), dp(6f), dp(4f))
        })
        addView(liveBody, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        resultSource.apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        resultText.apply {
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setTextIsSelectable(false)
        }
        resultStatus.maxLines = 2
        val pad = dp(10f)
        resultBody.addView(resultSource, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(pad, 0, pad, dp(2f))
        })
        resultBody.addView(resultText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(pad, 0, pad, dp(2f))
        })
        resultBody.addView(resultStatus, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(pad, 0, pad, dp(2f))
        })
        val buttons = LinearLayout(context).apply { orientation = HORIZONTAL }
        for (b in listOf(applyBtn, copyBtn, resultDownload)) {
            buttons.addView(b, LayoutParams(0, dp(34f), 1f).apply { setMargins(m, m, m, m) })
        }
        resultBody.addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(4f), 0, dp(4f), dp(2f))
        })
        addView(resultBody, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        pickerScroll.addView(pickerGrid)
        addView(pickerScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(150f)))
        setMode(Mode.LIVE)
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, click: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        val p = dp(10f)
        setPadding(p, 0, p, 0)
        isClickable = true
        setOnClickListener { click() }
    }

    private fun setMode(m: Mode) {
        mode = m
        liveBody.visibility = if (m == Mode.LIVE) View.VISIBLE else View.GONE
        resultBody.visibility = if (m == Mode.RESULT) View.VISIBLE else View.GONE
        pickerScroll.visibility = if (m == Mode.PICKER) View.VISIBLE else View.GONE
        style()
    }

    fun applyTheme(p: KbSettings.Palette, fontScale: Float) {
        pal = p
        scale = fontScale
        style()
        if (mode == Mode.PICKER) buildPicker()
    }

    private fun sp(v: Float) = v * scale

    private fun styleButton(b: TextView, selected: Boolean = false, filled: Boolean = false, size: Float = 13f) {
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(size))
        b.setTextColor(
            when {
                filled -> Color.WHITE
                selected -> pal.accent
                else -> pal.text
            }
        )
        b.setTypeface(null, if (selected || filled) Typeface.BOLD else Typeface.NORMAL)
        b.background = GradientDrawable().apply {
            cornerRadius = dp(16f).toFloat()
            if (filled) setColor(pal.accent) else {
                setColor(pal.funcTop)
                setStroke(dp(1f), if (selected) pal.accent else pal.funcStroke)
            }
        }
    }

    private fun style() {
        setBackgroundColor(pal.barBg)
        styleButton(closeBtn, size = 14f)
        styleButton(langBtn, selected = mode == Mode.PICKER, size = 14f)
        styleButton(liveBtn, selected = mode == Mode.LIVE)
        styleButton(fieldBtn)
        styleButton(clipBtn)
        styleButton(liveDownload, selected = !liveError, filled = liveError, size = 12f)
        styleButton(applyBtn, filled = true)
        styleButton(copyBtn)
        // перевод есть — главное «Заменить», скачать модели — второстепенно
        styleButton(resultDownload, selected = resultOk, filled = !resultOk)

        liveBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(16f))
        liveBox.setTextColor(pal.text)
        liveBox.setHintTextColor(pal.dimText)
        liveBox.background = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            setColor(pal.keyTop)
            setStroke(dp(1f), pal.accent)
        }
        liveStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(12f))
        liveStatus.setTextColor(if (liveError) 0xFFFF6B6B.toInt() else pal.dimText)

        resultSource.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(13f))
        resultSource.setTextColor(pal.dimText)
        resultText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(16f))
        resultText.setTextColor(pal.text)
        resultStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(12f))
        resultStatus.setTextColor(if (resultError) 0xFFFF6B6B.toInt() else pal.dimText)
    }

    private fun title(t: String) {
        langBtn.text = "$t ▾"
        langBtn.contentDescription = "Язык перевода: $t"
    }

    // ================================================================ набор

    fun showLive(title: String, hint: String) {
        title(title)
        liveBox.hint = hint
        setMode(Mode.LIVE)
    }

    /** Строка набора: текст, курсор и подчёркнутое набираемое слово. */
    fun setLiveText(text: String, caret: Int, compS: Int, compE: Int) {
        if (text.isEmpty()) {
            liveBox.text = ""
            return
        }
        val c = caret.coerceIn(0, text.length)
        // длинный текст — показываем окрестность курсора (в три строки всё не влезет)
        var from = maxOf(0, c - LIVE_WINDOW)
        if (from > 0) text.indexOf(' ', from).let { if (it in from until c) from = it + 1 }
        val to = minOf(text.length, c + LIVE_WINDOW / 2)
        val sb = SpannableStringBuilder(text.substring(from, to))
        val cs = compS - from
        val ce = minOf(compE, to) - from
        if (compS >= from && cs < ce) sb.setSpan(UnderlineSpan(), cs, ce, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val at = c - from
        sb.insert(at, "|")
        sb.setSpan(ForegroundColorSpan(pal.accent), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (from > 0) sb.insert(0, "…")
        if (to < text.length) sb.append("…")
        liveBox.text = sb
    }

    fun setLiveStatus(status: String, error: Boolean, missing: List<String>) {
        this.missing = missing
        liveError = error
        liveStatus.text = status
        liveDownload.visibility = if (missing.isNotEmpty()) View.VISIBLE else View.GONE
        style()
    }

    // ================================================================ результат

    /** [result] null — ещё переводится; [applyLabel] null — вставить некуда. */
    fun showResult(
        title: String, source: String, result: String?, status: String, error: Boolean,
        applyLabel: String?, missing: List<String> = emptyList()
    ) {
        title(title)
        this.missing = missing
        resultError = error
        resultSource.text = source
        resultText.text = result ?: ""
        resultText.visibility = if (result.isNullOrEmpty()) View.GONE else View.VISIBLE
        resultStatus.text = status
        resultStatus.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
        val ok = !result.isNullOrEmpty() && !error
        resultOk = ok
        applyBtn.text = applyLabel ?: ""
        applyBtn.visibility = if (ok && applyLabel != null) View.VISIBLE else View.GONE
        copyBtn.visibility = if (ok) View.VISIBLE else View.GONE
        resultDownload.visibility = if (missing.isNotEmpty()) View.VISIBLE else View.GONE
        setMode(Mode.RESULT)
    }

    // ================================================================ языки

    fun showPicker(title: String, current: String) {
        title(title)
        pickerCurrent = current
        setMode(Mode.PICKER)
        buildPicker()
    }

    private fun buildPicker() {
        pickerGrid.removeAllViews()
        val m = dp(2f)
        for (chunk in TranslationManager.LANGS.chunked(4)) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for ((code, name) in chunk) {
                val b = button(name) { host.onPickLanguage(code) }
                styleButton(b, selected = code == pickerCurrent, size = 12f)
                b.tag = code
                row.addView(b, LayoutParams(0, dp(34f), 1f).apply { setMargins(m, m, m, m) })
            }
            for (i in chunk.size until 4) row.addView(View(context), LayoutParams(0, dp(34f), 1f))
            pickerGrid.addView(row)
        }
        pickerGrid.setPadding(dp(4f), 0, dp(4f), dp(4f))
    }

    // для тестов
    internal val liveTextForTest: CharSequence get() = liveBox.text
    internal val liveStatusForTest: CharSequence get() = liveStatus.text
    internal val resultForTest: CharSequence get() = resultText.text
    internal val resultStatusForTest: CharSequence get() = resultStatus.text
    internal val applyVisibleForTest: Boolean get() = applyBtn.visibility == View.VISIBLE
    internal fun clickApplyForTest() = applyBtn.performClick()
    internal fun clickCopyForTest() = copyBtn.performClick()
    internal fun clickDownloadForTest() = (if (mode == Mode.LIVE) liveDownload else resultDownload).performClick()
    internal fun clickLanguageForTest(code: String) {
        for (i in 0 until pickerGrid.childCount) {
            val row = pickerGrid.getChildAt(i) as LinearLayout
            for (j in 0 until row.childCount) if (row.getChildAt(j).tag == code) row.getChildAt(j).performClick()
        }
    }
}
