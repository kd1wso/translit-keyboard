package com.example.translit

import android.content.Context
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * Управляет раскладками: встроенные + кастомные (JSON-файлы).
 *
 * Формат кастомной раскладки (файл в filesDir/layouts/имя.json):
 * {"id":"code","name":"Программист","rows":[[{"k":"q","lp":["Q"]},{"k":"w"}],...]}
 */
object KeyboardLayoutManager {

    data class KeyDef(val label: String, val longPress: List<String> = emptyList())
    data class LayoutDef(val id: String, val name: String, val rows: List<List<KeyDef>>)

    enum class Page { LETTERS, SYMBOLS1, SYMBOLS2, EMOJI, NUMBER, PHONE }

    private val LONG_PRESS = mapOf(
        // верхний ряд ЙЦУКЕН: цифры (подсказка в углу клавиши)
        "й" to listOf("1"), "ц" to listOf("2"), "у" to listOf("3"), "к" to listOf("4"),
        "е" to listOf("ё", "5"), "н" to listOf("6"), "г" to listOf("7"), "ш" to listOf("8"),
        "щ" to listOf("9"), "з" to listOf("0"), "ь" to listOf("ъ"),
        // верхний ряд QWERTY + диакритика
        "q" to listOf("1"), "w" to listOf("2"), "e" to listOf("3", "é", "è", "ê", "ë"),
        "r" to listOf("4"), "t" to listOf("5"), "y" to listOf("6", "ý", "ÿ"),
        "u" to listOf("7", "ü", "ú", "û"), "i" to listOf("8", "í", "ï", "î"),
        "o" to listOf("9", "ö", "ó", "ô", "ø"), "p" to listOf("0"),
        "a" to listOf("á", "à", "ä", "â", "å"), "s" to listOf("ß", "ś", "š"),
        "c" to listOf("ç", "č"), "n" to listOf("ñ", "ń"), "z" to listOf("ž", "ź", "ż"),
        "l" to listOf("ł"),
        // символы
        "-" to listOf("—", "–", "_", "•"), "." to listOf("…", "·"),
        "?" to listOf("¿"), "!" to listOf("¡"),
        "$" to listOf("₽", "€", "£", "¥", "₴", "₸", "₺", "¢"),
        "\"" to listOf("„", "“", "”", "«", "»"),
        "'" to listOf("‚", "‘", "’"),
        "%" to listOf("‰"), "+" to listOf("±"), "*" to listOf("★", "†"),
        "(" to listOf("[", "{", "<"), ")" to listOf("]", "}", ">"),
        "/" to listOf("\\", "|"),
        "©" to listOf("®", "™"), "§" to listOf("¶"), "£" to listOf("¢"),
        "=" to listOf("≠", "≈", "≤", "≥"),
        "0" to listOf("°", "∅"), "1" to listOf("¹", "½", "⅓", "¼"),
        "2" to listOf("²", "⅔"), "3" to listOf("³", "¾")
    )

    fun longPressFor(label: String): List<String> = LONG_PRESS[label] ?: emptyList()

    private fun row(vararg labels: String): List<KeyDef> =
        labels.map { KeyDef(it, longPressFor(it)) }

    val RU_LETTERS = LayoutDef("ru", "ЙЦУКЕН", listOf(
        row("й","ц","у","к","е","н","г","ш","щ","з","х","ъ"),
        row("ф","ы","в","а","п","р","о","л","д","ж","э"),
        row("я","ч","с","м","и","т","ь","б","ю")
    ))

    private fun k(label: String, vararg lp: String) = KeyDef(label, lp.toList())

    /** Фонетическая ЯВЕРТЫ: буквы на местах похожих латинских (я — q, в — w, ч — h). */
    val RU_PHONETIC = LayoutDef("ru_phonetic", "ЯВЕРТЫ", listOf(
        listOf(k("я", "1"), k("в", "2"), k("е", "ё", "3"), k("р", "4"), k("т", "5"), k("ы", "6"),
            k("у", "7"), k("и", "8"), k("о", "9"), k("п", "0"), k("ш"), k("щ")),
        listOf(k("а"), k("с"), k("д"), k("ф"), k("г"), k("ч"), k("й"), k("к"), k("л"), k("э"), k("ю")),
        listOf(k("з"), k("х"), k("ц"), k("ж"), k("б"), k("н"), k("м"), k("ь", "ъ"))
    ))

    val EN_LETTERS = LayoutDef("en", "QWERTY", listOf(
        row("q","w","e","r","t","y","u","i","o","p"),
        row("a","s","d","f","g","h","j","k","l"),
        row("z","x","c","v","b","n","m")
    ))

    /** Третий ряд символов дополняется клавишами «страница» и ⌫. */
    val SYMBOLS1 = LayoutDef("sym1", "Символы 1", listOf(
        row("1","2","3","4","5","6","7","8","9","0"),
        row("@","#","$","%","&","-","+","(",")","/"),
        row("*","\"","'",":",";","!","?")
    ))

    val SYMBOLS2 = LayoutDef("sym2", "Символы 2", listOf(
        row("~","`","|","•","√","π","÷","×","§","¶"),
        row("£","€","¥","^","°","=","{","}","\\","©"),
        row("_","<",">","[","]","«","»")
    ))

    val EMOJI = LayoutDef("emoji", "Эмодзи", listOf(
        listOf("\uD83D\uDE00","\uD83D\uDE02","\uD83E\uDD70","\uD83D\uDE0E","\uD83E\uDD14",
               "\uD83D\uDE22","\uD83D\uDE21","\uD83E\uDD73","\uD83D\uDE34","\uD83E\uDD2F").map { KeyDef(it) },
        listOf("\uD83D\uDC4D","\uD83D\uDC4E","\uD83D\uDE4F","\uD83D\uDC4B","\uD83D\uDCAA",
               "\uD83E\uDD1D","✌️","\uD83D\uDC4C","❤️","\uD83D\uDD25").map { KeyDef(it) },
        listOf("\uD83C\uDF89","✨","\uD83D\uDCAF","✅",
               "☀️","\uD83C\uDF08","\uD83C\uDF55","☕").map { KeyDef(it) }
    ))

    /** Цифровая клавиатура (числа, дата/время): последний столбец — служебные клавиши. */
    val NUMBER = LayoutDef("num", "Цифры", listOf(
        listOf(KeyDef("1"), KeyDef("2"), KeyDef("3"), KeyDef("-", listOf("+", "±", "×", "÷", "="))),
        listOf(KeyDef("4"), KeyDef("5"), KeyDef("6")),
        listOf(KeyDef("7"), KeyDef("8"), KeyDef("9")),
        listOf(KeyDef(",", listOf(":", "/", "%", "+", "*", "#")), KeyDef("0"), KeyDef(".", listOf("…", "°")))
    ))

    val PHONE = LayoutDef("phone", "Телефон", listOf(
        listOf(KeyDef("1"), KeyDef("2"), KeyDef("3"), KeyDef("+", listOf("-", "(", ")", ".", "/"))),
        listOf(KeyDef("4"), KeyDef("5"), KeyDef("6")),
        listOf(KeyDef("7"), KeyDef("8"), KeyDef("9")),
        listOf(KeyDef("*", listOf(",", ";", "N", "/")), KeyDef("0", listOf("+")), KeyDef("#"))
    ))

    /**
     * Соседние клавиши для автоисправления: буква → буквы, на которые легко промахнуться.
     * Ряды центрируются, как на экране.
     */
    fun proximity(def: LayoutDef): Map<Char, Set<Char>> {
        val maxLen = def.rows.maxOfOrNull { it.size } ?: 0
        data class P(val c: Char, val row: Int, val x: Float)
        val keys = ArrayList<P>()
        for ((r, row) in def.rows.withIndex()) {
            val off = (maxLen - row.size) / 2f
            for ((i, k) in row.withIndex()) {
                if (k.label.length == 1 && k.label[0].isLetter()) {
                    keys.add(P(k.label[0].lowercaseChar(), r, off + i + 0.5f))
                }
            }
        }
        val out = HashMap<Char, MutableSet<Char>>()
        for (a in keys) for (b in keys) {
            if (a.c == b.c) continue
            if (abs(a.row - b.row) <= 1 && abs(a.x - b.x) <= 1.01f) {
                out.getOrPut(a.c) { HashSet() }.add(b.c)
            }
        }
        return out
    }

    /** Язык словаря для раскладки: по преобладающему алфавиту. */
    fun languageOf(def: LayoutDef): String {
        var cyr = 0
        var lat = 0
        for (row in def.rows) for (k in row) for (c in k.label) {
            if (c in 'а'..'я' || c in 'А'..'Я' || c == 'ё' || c == 'Ё') cyr++
            else if (c in 'a'..'z' || c in 'A'..'Z') lat++
        }
        return when {
            cyr == 0 && lat == 0 -> ""
            cyr >= lat -> "ru"
            else -> "en"
        }
    }

    private var customCache: Pair<Long, List<LayoutDef>>? = null

    fun customLayouts(ctx: Context): List<LayoutDef> {
        val now = System.currentTimeMillis()
        customCache?.let { if (now - it.first < 30_000) return it.second }
        val dir = File(ctx.filesDir, "layouts")
        val result = mutableListOf<LayoutDef>()
        if (dir.isDirectory) {
            dir.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }?.forEach { f ->
                try {
                    val obj = JSONObject(f.readText())
                    val id = obj.optString("id", f.nameWithoutExtension)
                    val name = obj.optString("name", id)
                    val rowsArr = obj.getJSONArray("rows")
                    val rows = mutableListOf<List<KeyDef>>()
                    for (i in 0 until rowsArr.length()) {
                        val rowArr = rowsArr.getJSONArray(i)
                        val keys = mutableListOf<KeyDef>()
                        for (j in 0 until rowArr.length()) {
                            val k = rowArr.getJSONObject(j)
                            val label = k.optString("k", "")
                            val lpList = mutableListOf<String>()
                            k.optJSONArray("lp")?.let { arr ->
                                for (m in 0 until arr.length()) lpList.add(arr.getString(m))
                            }
                            if (label.isNotEmpty()) keys.add(KeyDef(label, lpList))
                        }
                        if (keys.isNotEmpty()) rows.add(keys)
                    }
                    if (rows.isNotEmpty()) result.add(LayoutDef(id, name, rows))
                } catch (_: Exception) {
                }
            }
        }
        customCache = now to result
        return result
    }

    fun invalidateCache() {
        customCache = null
    }
}
