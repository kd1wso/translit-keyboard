package com.example.translit

import java.text.Normalizer

/**
 * Текст, набранный не в той раскладке (как Punto Switcher): «ghbdtn» ↔ «привет», «руддщ» ↔ «hello».
 * Соответствие клавиш — как на компьютерной клавиатуре ЙЦУКЕН ↔ QWERTY (включая знаки:
 * «ghbdtn? rfr ltkf&» → «привет, как дела?»).
 */
object LayoutFix {

    private const val EN_KEYS = "`qwertyuiop[]asdfghjkl;'zxcvbnm,./" + "~QWERTYUIOP{}ASDFGHJKL:\"ZXCVBNM<>?" + "@#$^&|"
    private const val RU_KEYS = "ёйцукенгшщзхъфывапролджэячсмитьбю." + "ЁЙЦУКЕНГШЩЗХЪФЫВАПРОЛДЖЭЯЧСМИТЬБЮ," + "\"№;:?/"

    /** Фонетическая ЯВЕРТЫ этой клавиатуры → клавиши QWERTY на тех же местах. */
    private const val YAV_RU = "явертыуиопшщасдфгчйклэюзхцжбнмь"
    private const val YAV_EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,"

    private val enToRu = HashMap<Char, Char>()
    private val ruToEn = HashMap<Char, Char>()
    private val yavToEn = HashMap<Char, Char>()

    init {
        require(EN_KEYS.length == RU_KEYS.length)
        for (i in EN_KEYS.indices) {
            enToRu[EN_KEYS[i]] = RU_KEYS[i]
            ruToEn[RU_KEYS[i]] = EN_KEYS[i]
        }
        for (i in YAV_RU.indices) {
            yavToEn[YAV_RU[i]] = YAV_EN[i]
            yavToEn[YAV_RU[i].uppercaseChar()] = YAV_EN[i].let { if (it.isLetter()) it.uppercaseChar() else it }
        }
    }

    private fun map(s: CharSequence, m: Map<Char, Char>): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(m[c] ?: c)
        return sb.toString()
    }

    /** Латиница, набранная вместо ЙЦУКЕН: «ghbdtn» → «привет». */
    fun toRu(s: CharSequence): String = map(s, enToRu)

    /** Кириллица, набранная вместо QWERTY: «руддщ» → «hello». */
    fun toEn(s: CharSequence, yaverty: Boolean = false): String = map(s, if (yaverty) yavToEn else ruToEn)

    /** Слово целиком из букв (апостроф и дефис внутри — можно). */
    fun isWord(s: String): Boolean =
        s.isNotEmpty() && s[0].isLetter() && s.all { it.isLetter() || it == '\'' || it == '-' }

    /** Направление по большинству букв: латиница → ЙЦУКЕН, кириллица → QWERTY. */
    fun swap(s: CharSequence): String {
        var lat = 0
        var cyr = 0
        for (c in s) {
            if (c in 'a'..'z' || c in 'A'..'Z') lat++
            else if (c in 'а'..'я' || c in 'А'..'Я' || c == 'ё' || c == 'Ё') cyr++
        }
        return if (lat >= cyr) toRu(s) else toEn(s)
    }
}

/** Действия с выделенным текстом (подсказки над клавиатурой). */
object TextActions {

    enum class Action(val label: String) {
        LATIN("→ латиница"),
        CYRILLIC("→ кириллица"),
        LAYOUT("⇄ раскладка"),
        SLUG("→ ссылка")
    }

    private fun hasCyr(s: CharSequence) = s.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
    private fun hasLat(s: CharSequence) = s.any { it in 'a'..'z' || it in 'A'..'Z' }

    fun available(text: CharSequence): List<Action> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<Action>(4)
        val cyr = hasCyr(text)
        val lat = hasLat(text)
        if (cyr) out.add(Action.LATIN)
        if (lat) out.add(Action.CYRILLIC)
        if (cyr || lat) out.add(Action.LAYOUT)
        if (text.any { it.isLetterOrDigit() } && slug(text.toString()) != text.toString()) out.add(Action.SLUG)
        return out
    }

    fun apply(a: Action, text: String, mode: TranslitMode): String = when (a) {
        Action.LATIN -> mode.translitWord(text)
        Action.CYRILLIC -> ReverseTranslit.convertText(text)
        Action.LAYOUT -> LayoutFix.swap(text)
        Action.SLUG -> slug(text)
    }

    /** Часть ссылки: «Привет, мир!» → «privet-mir». */
    fun slug(text: String): String {
        val lat = TranslitMode.COLLOQUIAL.translitWord(text.lowercase())
        val plain = Normalizer.normalize(lat, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val sb = StringBuilder(plain.length)
        var dash = false
        for (c in plain.lowercase()) {
            when {
                c in 'a'..'z' || c in '0'..'9' -> {
                    if (dash && sb.isNotEmpty()) sb.append('-')
                    sb.append(c)
                    dash = false
                }
                c == '\'' || c == '’' -> Unit // don't → dont
                else -> dash = true
            }
        }
        return sb.toString()
    }
}
