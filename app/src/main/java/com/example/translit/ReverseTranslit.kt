package com.example.translit

import kotlin.math.ln
import kotlin.math.min

/**
 * Обратный транслит: латиница → кириллица («privet» → «привет», «xorosho» → «хорошо»).
 *
 * Разбор неоднозначен: «ts» — это «ц» или «тс» (отсюда), «e» — «е» или «э» (это),
 * «ya» после согласной — «я» или «ья» (семья), мягкий знак часто не пишут (den → день).
 * Поэтому строятся варианты со штрафами, а выбирается тот, что лучше всего подходит по словарю.
 * Все варианты — в нижнем регистре; регистр набранного переносит InputEngine.
 */
object ReverseTranslit {

    class Cand(val text: String, val pen: Double)

    private class Opt(val out: String, val pen: Double)

    private const val BEAM = 40
    /** Штраф за то, что буквосочетание разобрано по частям (s+h вместо sh). */
    private const val SPLIT = 1.5
    /** Мягкий знак в конце слова, которого не было в наборе. */
    private const val END_SOFT = 2.5
    /** Показанный при наборе вариант сохраняется, если он не хуже лучшего. */
    private const val STAY = 0.5

    private const val VOWELS = "аеёиоуыэюя"
    private const val CONSONANTS = "бвгджзйклмнпрстфхцчшщ"
    private const val SOFTENABLE = "бвдзлмнпрстфцжчшщ"

    private fun o(vararg p: Any): Array<Opt> =
        Array(p.size / 2) { Opt(p[it * 2] as String, (p[it * 2 + 1] as Number).toDouble()) }

    private val ONE: Map<String, Array<Opt>> = mapOf(
        "a" to o("а", 0), "b" to o("б", 0), "d" to o("д", 0), "f" to o("ф", 0), "g" to o("г", 0),
        "h" to o("х", 0), "k" to o("к", 0), "l" to o("л", 0), "m" to o("м", 0), "n" to o("н", 0),
        "o" to o("о", 0), "p" to o("п", 0), "r" to o("р", 0), "s" to o("с", 0), "t" to o("т", 0),
        "v" to o("в", 0), "z" to o("з", 0),
        "c" to o("ц", 0, "с", 0.7, "к", 1.0),
        "j" to o("й", 0, "ж", 0.8, "дж", 1.5),
        "q" to o("к", 0, "я", 1.5),
        "u" to o("у", 0, "ю", 1.2),
        "w" to o("в", 0, "ш", 0.8, "щ", 1.5),
        "x" to o("х", 0, "кс", 0.6),
        "'" to o("ь", 0, "ъ", 0.5),
        "shch" to o("щ", 0, "шч", 2.0),
        "sch" to o("щ", 0, "сч", 0.3),
        "shh" to o("щ", 0),
        "sh" to o("ш", 0, "сх", 1.2),
        "zh" to o("ж", 0, "зх", 2.0),
        "kh" to o("х", 0, "кх", 2.0),
        "ch" to o("ч", 0),
        "ts" to o("ц", 0, "тс", 0.4),
        "cz" to o("ц", 0),
        "ia" to o("иа", 0, "ия", 0.6, "я", 1.2),
        "iu" to o("иу", 0, "ию", 0.6, "ю", 1.2),
        "''" to o("ъ", 0)
    )

    // варианты, зависящие от предыдущей буквы
    private val E_START = o("е", 0, "э", 0.4)
    private val E_MID = o("е", 0, "э", 1.2)
    private val I_AFTER_VOWEL = o("и", 0, "й", 1.2, "ы", 1.5)
    private val I_MID = o("и", 0, "ы", 1.0, "й", 1.5)
    private val Y_AFTER_CONS = o("ы", 0, "й", 1.0, "ь", 1.5)
    private val Y_OTHER = o("й", 0, "ы", 1.0)
    private val YA_CONS = o("я", 0, "ья", 0.4, "ъя", 0.8)
    private val YA_OTHER = o("я", 0, "йа", 2.0)
    private val YU_CONS = o("ю", 0, "ью", 0.4, "ъю", 1.0)
    private val YU_OTHER = o("ю", 0)
    private val YO_CONS = o("ё", 0, "ьё", 0.4, "ъё", 1.0, "ьо", 0.8)
    private val YO_OTHER = o("ё", 0, "йо", 0.6)
    private val YE_CONS = o("ые", 0, "ье", 0.5, "ъе", 0.6, "е", 1.0)
    private val YE_OTHER = o("е", 0, "йе", 1.2, "э", 1.5)

    private fun options(p: String, prev: Char?): Array<Opt>? {
        val cons = prev != null && CONSONANTS.indexOf(prev) >= 0
        return when (p) {
            "e" -> if (prev == null) E_START else E_MID
            "i" -> if (prev != null && VOWELS.indexOf(prev) >= 0) I_AFTER_VOWEL else I_MID
            "y" -> if (cons) Y_AFTER_CONS else Y_OTHER
            "ya", "ja" -> if (cons) YA_CONS else YA_OTHER
            "yu", "ju" -> if (cons) YU_CONS else YU_OTHER
            "yo", "jo" -> if (cons) YO_CONS else YO_OTHER
            "ye", "je" -> if (cons) YE_CONS else YE_OTHER
            else -> ONE[p]
        }
    }

    private fun hasRule(p: String): Boolean = options(p, null) != null

    /** Латинская буква (или апостроф), которую понимает обратный транслит. */
    fun isSourceChar(c: Char): Boolean {
        val l = c.lowercaseChar()
        return l in 'a'..'z' || l == '\'' || l == '’'
    }

    private fun normalizeSrc(src: CharSequence): String {
        val sb = StringBuilder(src.length)
        for (c in src) sb.append(if (c == '’' || c == 'ʼ' || c == '‘') '\'' else c.lowercaseChar())
        return sb.toString()
    }

    private fun put(m: HashMap<String, Double>, t: String, p: Double) {
        val old = m[t]
        if (old == null || p < old) m[t] = p
    }

    private fun top(m: Map<String, Double>, k: Int): List<Map.Entry<String, Double>> =
        m.entries.sortedWith(compareBy<Map.Entry<String, Double>> { it.value }.thenBy { it.key.length })
            .let { if (it.size > k) it.subList(0, k) else it }

    private var cacheKey: String? = null
    private var cacheVal: List<Cand> = emptyList()

    /** Варианты прочтения по возрастанию штрафа; первый — «жадный» разбор. */
    @Synchronized
    fun candidates(src: CharSequence): List<Cand> {
        val s = normalizeSrc(src)
        if (s == cacheKey) return cacheVal
        val n = s.length
        val beams = Array(n + 1) { HashMap<String, Double>() }
        beams[0][""] = 0.0
        for (i in 0 until n) {
            if (beams[i].isEmpty()) continue
            val cur = top(beams[i], BEAM)
            var longest = 0
            for (len in min(4, n - i) downTo 1) if (hasRule(s.substring(i, i + len))) {
                longest = len
                break
            }
            if (longest == 0) {
                // дефис, цифра, чужая буква — как есть
                for (e in cur) put(beams[i + 1], e.key + s[i], e.value)
                continue
            }
            for (len in longest downTo 1) {
                val pat = s.substring(i, i + len)
                val split = if (len < longest) SPLIT else 0.0
                for (e in cur) {
                    val opts = options(pat, e.key.lastOrNull()) ?: continue
                    for (op in opts) put(beams[i + len], e.key + op.out, e.value + op.pen + split)
                }
            }
        }
        val end = beams[n]
        for ((t, p) in end.entries.toList()) {
            if (t.isNotEmpty() && SOFTENABLE.indexOf(t.last()) >= 0) put(end, t + "ь", p + END_SOFT)
        }
        val res = if (end.isEmpty()) listOf(Cand(s, 0.0)) else top(end, BEAM).map { Cand(it.key, it.value) }
        cacheKey = s
        cacheVal = res
        return res
    }

    /** «Жадное» прочтение без словаря. */
    fun decode(src: CharSequence): String = candidates(src).first().text

    /**
     * Что показывать, пока слово набирается: вариант, с которого начинается частое слово
     * («privets» → «привет…», «otsyu» → «отсю…»); если такого нет — жадный разбор.
     */
    fun live(src: CharSequence, lang: String = "ru"): String {
        val cands = candidates(src)
        if (!SuggestionEngine.isLoaded(lang)) return cands.first().text
        var best: Cand? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (c in cands) {
            val cnt = SuggestionEngine.prefixCount(lang, WordDictionary.normalize(c.text))
            if (cnt <= 0) continue
            val s = ln(cnt + 1.0) - c.pen
            if (s > bestScore) {
                bestScore = s
                best = c
            }
        }
        return (best ?: cands.first()).text
    }

    /**
     * Известные слова среди вариантов, лучшие первыми (текст варианта — с «ё», если её набрали).
     * [shown] — что сейчас на экране: при равенстве оно сохраняется.
     */
    fun knownWords(src: CharSequence, shown: String?, lang: String = "ru", limit: Int = 4): List<String> {
        if (!SuggestionEngine.isLoaded(lang)) return emptyList()
        val scored = ArrayList<Pair<String, Double>>()
        val seen = HashSet<String>()
        for (c in candidates(src)) {
            val key = WordDictionary.normalize(c.text)
            if (!seen.add(key)) continue
            val cnt = SuggestionEngine.wordCount(lang, key)
            if (cnt <= 0) continue
            scored.add(c.text to ln(cnt + 1.0) - c.pen + (if (c.text == shown) STAY else 0.0))
        }
        return scored.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    /** Текст целиком: слова латиницей → кириллица по словарю, остальное как есть. */
    fun convertText(text: String, lang: String = "ru"): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val l = c.lowercaseChar()
            if (l !in 'a'..'z') {
                sb.append(c)
                i++
                continue
            }
            // слово: латинские буквы и апострофы после них (den' → день, ob''ekt → объект)
            var j = i
            while (j < text.length && (text[j].lowercaseChar() in 'a'..'z' || text[j] == '\'' || text[j] == '’')) j++
            val token = text.substring(i, j)
            val word = knownWords(token, null, lang, 1).firstOrNull() ?: live(token, lang)
            sb.append(caseLike(word, token))
            i = j
        }
        return sb.toString()
    }

    /** Регистр как у набранного: «Privet» → «Привет», «PRIVET» → «ПРИВЕТ». */
    fun caseLike(w: String, typed: CharSequence): String {
        var letters = 0
        var upper = 0
        for (c in typed) if (c.isLetter()) {
            letters++
            if (c.isUpperCase()) upper++
        }
        return when {
            letters >= 2 && upper == letters -> w.uppercase()
            typed.isNotEmpty() && typed[0].isUpperCase() -> w.replaceFirstChar { it.uppercaseChar() }
            else -> w
        }
    }
}
