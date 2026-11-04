package com.example.translit

import java.io.File

object Dicts {
    private var loaded = false

    @Synchronized
    fun ensure() {
        if (loaded) return
        for (lang in listOf("ru", "en")) {
            File("src/main/assets/dict/$lang.fixes.tsv").inputStream().use {
                SuggestionEngine.putFixes(lang, SuggestionEngine.parseFixes(it))
            }
            File("src/main/assets/dict/$lang.tsv").inputStream().use { SuggestionEngine.put(lang, WordDictionary.load(it)) }
        }
        loaded = true
    }
}

/** Клавиатура + поле ввода; [type] печатает строку: ' ' — пробел, '\n' — Enter, '⌫' — удаление. */
class Harness(
    profile: FieldProfile = NO_CAPS,
    lang: String = "ru",
    translit: TranslitMode? = null,
    settings: EngineSettings = EngineSettings(),
    val autoDeliver: Boolean = true,
    reverse: Boolean = false,
    /** Исправление раскладки (как в сервисе): 0 — выкл., 1 — ЙЦУКЕН ↔ QWERTY, 2 — ЯВЕРТЫ. */
    layoutFixMode: Int = 0
) {
    companion object {
        val NO_CAPS = FieldProfile.TEXT.copy(capsModes = 0)
    }

    /** Раскладка, как её настраивает сервис. */
    data class Conf(val lang: String, val translit: TranslitMode?, val reverse: Boolean)

    val ed = FakeEditor()
    val eng = InputEngine(ed)
    var now = 1_000_000L

    /** Запросы на переключение раскладки от исправления раскладки. */
    val switches = ArrayList<String>()
    var conf = Conf(lang, translit, reverse)
        private set
    private var beforeFix: Conf? = null

    fun apply(c: Conf) {
        conf = c
        val keysLatin = c.lang == "en" || c.reverse
        val def = if (keysLatin) KeyboardLayoutManager.EN_LETTERS else KeyboardLayoutManager.RU_LETTERS
        eng.configure(c.lang, c.translit, KeyboardLayoutManager.proximity(def), c.reverse)
    }

    init {
        Dicts.ensure()
        eng.clock = { now }
        eng.settings = settings
        eng.layoutFixMode = layoutFixMode
        eng.onSwitchLanguage = { t ->
            switches.add(t)
            if (t == InputEngine.SWITCH_BACK) {
                beforeFix?.let { apply(it) }
                beforeFix = null
            } else {
                beforeFix = conf
                apply(Conf(t, null, false))
            }
        }
        apply(conf)
        eng.startInput(profile, 0, 0)
        deliver()
    }

    fun deliver() {
        while (ed.updates.isNotEmpty()) {
            val u = ed.updates.removeFirst()
            eng.onUpdateSelection(u[0], u[1], u[2], u[3], u[4], u[5])
        }
    }

    /** Доставить одно отложенное onUpdateSelection (имитация запаздывания). */
    fun deliverOne() {
        val u = ed.updates.removeFirstOrNull() ?: return
        eng.onUpdateSelection(u[0], u[1], u[2], u[3], u[4], u[5])
    }

    fun step(block: InputEngine.() -> Unit) {
        now += 150
        eng.block()
        if (autoDeliver) deliver()
    }

    fun type(s: String) {
        for (ch in s) step {
            when (ch) {
                ' ' -> onSpace()
                '\n' -> onEnter()
                '⌫' -> onBackspace()
                else -> if (ch.isLetter()) onLetter(ch) else onText(ch.toString())
            }
        }
    }

    fun shift() = step { onShiftTap() }

    val text: String get() = ed.text.toString()
    val chips: List<String> get() = eng.chips.map { it.label }

    /** Текст в поле и выделение, как будто их сделал пользователь. */
    fun select(text: String, start: Int = 0, end: Int = text.length) {
        ed.text.setLength(0)
        ed.text.append(text)
        ed.userSelect(start, end)
        deliver()
    }

    val selection: String get() = ed.text.substring(minOf(ed.selS, ed.selE), maxOf(ed.selS, ed.selE))

    fun pick(label: String) {
        val i = eng.chips.indexOfFirst { it.label == label }
        require(i >= 0) { "нет подсказки «$label» среди ${chips}" }
        step { pickChip(i) }
    }
}
