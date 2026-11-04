package com.example.translit

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : Activity() {

    private lateinit var accentRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(20f).toInt()
            setPadding(p, p, p, p)
        }
        scroll.addView(content)

        content.addView(header("Translit Keyboard"))
        content.addView(note("Русская раскладка, печатающая латиницей: «привет» → «privet». " +
            "Переключение раскладок — клавиша 🌐 на клавиатуре, настройки — ⚙ над клавишами."))

        // --- Активация
        content.addView(section("Активация"))
        content.addView(button("1. Включить клавиатуру в настройках") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        content.addView(button("2. Выбрать Translit Keyboard") {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        })

        // --- Раскладки
        content.addView(section("Раскладка по умолчанию"))
        content.addView(radioGroup(listOf(
            "TRANSLIT" to "Транслит (привет → privet)",
            "CYRILLIC" to "Русская (кириллица)",
            "REVERSE" to "Обратный транслит (privet → привет)",
            "LATIN" to "Английская (QWERTY)"
        ), KbSettings.get(this).layoutName) { v -> KbSettings.update(this) { it.copy(layoutName = v) } })
        content.addView(switchRow("Запоминать раскладку для каждого приложения", KbSettings.get(this).perAppLayout) { checked ->
            KbSettings.update(this) { it.copy(perAppLayout = checked) }
            if (!checked) KbSettings.clearAppLayouts(this)
        })

        content.addView(section("Раскладки на клавише 🌐"))
        content.addView(switchRow("Транслит", KbSettings.get(this).cycleTranslit) { checked ->
            KbSettings.update(this) { it.copy(cycleTranslit = checked) }
        })
        content.addView(switchRow("Русская", KbSettings.get(this).cycleCyrillic) { checked ->
            KbSettings.update(this) { it.copy(cycleCyrillic = checked) }
        })
        content.addView(switchRow("Обратный транслит (латиницей → по-русски)", KbSettings.get(this).cycleReverse) { checked ->
            KbSettings.update(this) { it.copy(cycleReverse = checked) }
        })
        content.addView(switchRow("Английская", KbSettings.get(this).cycleLatin) { checked ->
            KbSettings.update(this) { it.copy(cycleLatin = checked) }
        })
        content.addView(note("Обратный транслит: печатаете латиницей «privet, kak dela» — в поле " +
            "«привет, как дела». Неоднозначное (ts — «ц» или «тс», e — «е» или «э») решает словарь. " +
            "Свои раскладки из JSON тоже попадают на 🌐."))

        content.addView(section("Русские буквы"))
        content.addView(radioGroup(listOf(
            KbSettings.RU_JCUKEN to "ЙЦУКЕН (как на компьютере)",
            KbSettings.RU_YAVERTY to "ЯВЕРТЫ (фонетическая: я — q, в — w, ч — h)"
        ), KbSettings.get(this).ruLayout) { v -> KbSettings.update(this) { it.copy(ruLayout = v) } })

        // --- Транслит
        content.addView(section("Способ транслитерации"))
        val preview = note("")
        val customBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun updatePreview() {
            val st = KbSettings.get(this)
            TranslitMode.customTable = TranslitMode.parseCustom(st.customTable)
            val sample = "Привет, щука! Юлия, Елена, подъезд"
            preview.text = "$sample → ${st.translitMode.translitWord(sample)}"
        }
        fun renderCustomEditor() {
            customBox.removeAllViews()
            val st = KbSettings.get(this)
            if (st.translitMode != TranslitMode.CUSTOM) return
            customBox.addView(note("Латиница для каждой буквы (пусто — буква не выводится):"))
            val table = HashMap(TranslitMode.parseCustom(st.customTable))
            for (chunk in TranslitMode.ALPHABET.toList().chunked(3)) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (c in chunk) {
                    val cell = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    cell.addView(TextView(this).apply {
                        text = "$c ="
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        setTextColor(textColor())
                        setPadding(0, 0, dp(4f).toInt(), 0)
                    })
                    cell.addView(EditText(this).apply {
                        setText(table[c] ?: "")
                        setSingleLine()
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        setTextColor(textColor())
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, d: Int) {}
                            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, d: Int) {}
                            override fun afterTextChanged(e: Editable?) {
                                table[c] = e?.toString()?.trim()?.lowercase() ?: ""
                                KbSettings.update(this@MainActivity) { it.copy(customTable = TranslitMode.serializeCustom(table)) }
                                updatePreview()
                            }
                        })
                    })
                    row.addView(cell)
                }
                customBox.addView(row)
            }
            customBox.addView(button("Сбросить к разговорной") {
                KbSettings.update(this) { it.copy(customTable = "") }
                renderCustomEditor()
                updatePreview()
            })
        }
        content.addView(radioGroup(
            TranslitMode.values().map { it.name to "${it.title} (${it.example})" },
            KbSettings.get(this).translitMode.name
        ) { v ->
            KbSettings.update(this) { it.copy(translitMode = TranslitMode.byName(v)) }
            updatePreview()
            renderCustomEditor()
        })
        updatePreview()
        renderCustomEditor()
        content.addView(preview)
        content.addView(customBox)

        // --- Тема
        content.addView(section("Тема оформления"))
        val themeScroll = android.widget.HorizontalScrollView(this)
        val themeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        themeScroll.addView(themeRow)
        content.addView(themeScroll)
        fun renderThemes() {
            themeRow.removeAllViews()
            val cur = KbSettings.get(this)
            for (t in KbSettings.THEMES) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    val sel = t.id == cur.themeId
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14f)
                        setColor(t.palette.bg)
                        setStroke(dp(if (sel) 2.5f else 1f).toInt(),
                            if (sel) t.palette.accent else 0x33FFFFFF)
                    }
                    val m = dp(5f).toInt()
                    layoutParams = LinearLayout.LayoutParams(dp(96f).toInt(), dp(110f).toInt())
                        .apply { setMargins(m, m, m, m) }
                    setPadding(dp(8f).toInt(), dp(10f).toInt(), dp(8f).toInt(), dp(8f).toInt())
                    setOnClickListener {
                        KbSettings.update(this@MainActivity) { it.copy(themeId = t.id) }
                        recreate()
                    }
                }
                // мини-превью: три ряда клавиш
                for (rowIdx in 0 until 3) {
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp(14f).toInt()
                        )
                        lp.bottomMargin = dp(3f).toInt()
                        layoutParams = lp
                    }
                    for (i in 0 until 4) {
                        val key = View(this).apply {
                            background = GradientDrawable().apply {
                                cornerRadius = dp(4f)
                                setColor(
                                    when {
                                        rowIdx == 2 && i == 3 -> t.palette.accent
                                        rowIdx == 0 -> t.palette.keyTop
                                        else -> t.palette.funcTop
                                    }
                                )
                            }
                            layoutParams = LinearLayout.LayoutParams(
                                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
                            ).apply { marginEnd = dp(2f).toInt() }
                        }
                        row.addView(key)
                    }
                    card.addView(row)
                }
                card.addView(TextView(this).apply {
                    text = t.name
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(t.palette.text)
                    gravity = Gravity.CENTER
                })
                themeRow.addView(card)
            }
        }
        renderThemes()

        content.addView(note("Цвет акцента (пустой кружок — цвет темы)"))
        accentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, dp(8f).toInt(), 0, dp(8f).toInt())
            layoutParams = lp
        }
        content.addView(accentRow)
        renderAccents()

        // --- Размеры
        content.addView(section("Размеры"))
        content.addView(slider("Высота клавиш", 0.8f, 1.6f, KbSettings.get(this).keyHeight) { v ->
            KbSettings.update(this) { it.copy(keyHeight = v) }
        })
        content.addView(slider("Размер шрифта", 0.8f, 1.4f, KbSettings.get(this).fontSize) { v ->
            KbSettings.update(this) { it.copy(fontSize = v) }
        })

        // --- Подсказки и автоисправление
        content.addView(section("Подсказки и автоисправление"))
        content.addView(note("Работают во всех раскладках, включая транслит: словари ~48 тыс. " +
            "русских и английских слов плюс слова, которые вы печатаете. Опечатка исправляется " +
            "только при высокой уверенности, настоящие слова не трогаются; ⌫ сразу после " +
            "исправления возвращает набранное слово. В паролях подсказки и обучение выключены."))
        content.addView(switchRow("Панель подсказок", KbSettings.get(this).suggestionsEnabled) { checked ->
            KbSettings.update(this) { it.copy(suggestionsEnabled = checked) }
        })
        content.addView(switchRow("Автоисправление опечаток", KbSettings.get(this).autocorrect) { checked ->
            KbSettings.update(this) { it.copy(autocorrect = checked) }
        })
        content.addView(switchRow("Запоминать новые слова", KbSettings.get(this).learnWords) { checked ->
            KbSettings.update(this) { it.copy(learnWords = checked) }
        })
        content.addView(switchRow("Исправлять раскладку (ghbdtn → привет)", KbSettings.get(this).layoutFix) { checked ->
            KbSettings.update(this) { it.copy(layoutFix = checked) }
        })
        content.addView(note("Слово, набранное не в той раскладке, заменяется по пробелу, и раскладка " +
            "переключается; ⌫ сразу после замены возвращает как было. Выделите текст — над клавиатурой " +
            "появятся действия: → латиница, → кириллица, ⇄ раскладка, → ссылка (privet-mir)."))
        content.addView(button("Забыть выученные слова") {
            UserHistory.load(java.io.File(filesDir, "user_history.tsv"))
            UserHistory.clear()
            android.widget.Toast.makeText(this, "Выученные слова удалены", android.widget.Toast.LENGTH_SHORT).show()
        })

        // --- Ввод
        content.addView(section("Ввод"))
        content.addView(switchRow("Заглавная в начале предложения", KbSettings.get(this).autoCaps) { checked ->
            KbSettings.update(this) { it.copy(autoCaps = checked) }
        })
        content.addView(switchRow("Точка двойным пробелом", KbSettings.get(this).doubleSpacePeriod) { checked ->
            KbSettings.update(this) { it.copy(doubleSpacePeriod = checked) }
        })
        content.addView(note("Жесты: свайп по пробелу двигает курсор; свайп влево от ⌫ выделяет " +
            "слова и удаляет их; удержание ⌫ — быстрое удаление; удержание клавиши — варианты " +
            "(цифры, ё, символы), выбор скольжением; двойное нажатие ⇧ — Caps Lock; " +
            "удержание 🌐 или пробела — выбор клавиатуры."))

        // --- Отклик
        content.addView(section("Отклик клавиш"))
        content.addView(switchRow("Вибрация", KbSettings.get(this).vibrate) { checked ->
            KbSettings.update(this) { it.copy(vibrate = checked) }
        })
        content.addView(switchRow("Звук нажатия", KbSettings.get(this).sound) { checked ->
            KbSettings.update(this) { it.copy(sound = checked) }
        })

        val bg = KbSettings.palette(KbSettings.get(this)).bg
        scroll.setBackgroundColor(bg)
        setContentView(scroll)
        SystemBars.style(window, bg)
        // Android 15+ рисует экран и под панелью навигации — последний пункт не должен под ней прятаться
        scroll.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(nav.left, 0, nav.right, nav.bottom)
            insets
        }
    }

    private fun renderAccents() {
        accentRow.removeAllViews()
        val stNow = KbSettings.get(this)
        val current = stNow.accentOverride
        val themeAccent = KbSettings.theme(stNow.themeId).palette.accent

        // кружок «цвет темы» — сбрасывает переопределение
        val reset = View(this).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeAccent)
                if (current == null) setStroke(dp(3f).toInt(), KbSettings.palette(stNow).text)
                else setStroke(dp(1f).toInt(), 0x44FFFFFF)
            }
            background = bg
            val m = dp(6f).toInt()
            layoutParams = LinearLayout.LayoutParams(dp(40f).toInt(), dp(40f).toInt())
                .apply { setMargins(m, m, m, m) }
            setOnClickListener {
                KbSettings.update(this@MainActivity) { it.copy(accentOverride = null) }
                renderAccents()
            }
        }
        accentRow.addView(reset)

        for (c in KbSettings.ACCENTS) {
            val v = View(this).apply {
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    if (c == current) setStroke(dp(3f).toInt(), KbSettings.palette(stNow).text)
                    else setStroke(dp(1f).toInt(), 0x44FFFFFF)
                }
                background = bg
                val m = dp(6f).toInt()
                layoutParams = LinearLayout.LayoutParams(dp(40f).toInt(), dp(40f).toInt())
                    .apply { setMargins(m, m, m, m) }
                setOnClickListener {
                    KbSettings.update(this@MainActivity) { it.copy(accentOverride = c) }
                    renderAccents()
                }
            }
            accentRow.addView(v)
        }
    }

    private fun textColor(): Int = KbSettings.palette(KbSettings.get(this)).text

    /** Список вариантов: [options] — значение → подпись. */
    private fun radioGroup(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit): RadioGroup {
        val group = RadioGroup(this)
        for ((value, label) in options) {
            val rb = RadioButton(this).apply {
                text = label
                id = View.generateViewId()
                tag = value
                setTextColor(textColor())
            }
            group.addView(rb)
            if (value == current) group.check(rb.id)
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val rb = g.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            if (rb.isChecked) onPick(rb.tag as String)
        }
        return group
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        setTextColor(KbSettings.palette(KbSettings.get(context)).text)
        setTypeface(null, android.graphics.Typeface.BOLD)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(4f).toInt()
        layoutParams = lp
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(KbSettings.palette(KbSettings.get(context)).accent)
        setTypeface(null, android.graphics.Typeface.BOLD)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(22f).toInt()
        lp.bottomMargin = dp(6f).toInt()
        layoutParams = lp
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(KbSettings.palette(KbSettings.get(context)).dimText)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(6f).toInt()
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    private fun switchRow(text: String, initial: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(4f).toInt()
            layoutParams = lp
        }
        val label = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(KbSettings.palette(KbSettings.get(context)).text)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = Switch(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        row.addView(label)
        row.addView(sw)
        return row
    }

    private fun slider(
        text: String, min: Float, max: Float, initial: Float, onChange: (Float) -> Unit
    ): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(10f).toInt()
            layoutParams = lp
        }
        val label = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(KbSettings.palette(KbSettings.get(context)).text)
        }
        fun fmt(v: Float) = String.format("%.0f%%", v * 100)
        label.text = "$text — ${fmt(initial)}"
        val bar = SeekBar(this).apply {
            this.max = 100
            progress = (((initial - min) / (max - min)) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val v = min + (max - min) * p / 100f
                    label.text = "$text — ${fmt(v)}"
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        wrap.addView(label)
        wrap.addView(bar)
        return wrap
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
