package com.example.translit

/**
 * Системы транслитерации кириллицы латиницей.
 * У ГОСТ и BGN/PCGN буква зависит от соседних («ц» перед «и» — c, «е» в начале слова — ye),
 * поэтому слово транслитерируется целиком: [translitWord].
 */
enum class TranslitMode(val title: String, val spaceLabel: String, val example: String) {
    COLLOQUIAL("Разговорная", "транслит", "й→y, ц→c, х→h, щ→sch"),
    PASSPORT("Паспортная ICAO", "транслит ICAO", "й→i, ц→ts, х→kh, щ→shch"),
    GOST("ГОСТ 7.79-2000 (Б)", "транслит ГОСТ", "й→j, ц→cz, х→x, щ→shh, ь→`"),
    BGN("BGN/PCGN", "транслит BGN", "Елена→Yelena, Фёдор→Fyodor"),
    CUSTOM("Своя таблица", "транслит ✎", "буквы задаёте вы");

    private val table: Map<Char, String>
        get() = when (this) {
            COLLOQUIAL -> colloquial
            PASSPORT -> passport
            GOST -> gost
            BGN -> bgn
            CUSTOM -> customTable
        }

    /**
     * Транслитерация одной буквы без учёта соседей (подпись клавиши, ввод по буквам).
     * [upper] — буква заглавная; [allCaps] — слово набирается целиком заглавными
     * (Caps Lock или соседние буквы заглавные): «Ж» → «ZH», а не «Zh».
     */
    fun translit(ch: Char, upper: Boolean, allCaps: Boolean = false): String {
        val out = table[ch.lowercaseChar()]
            ?: return if (upper) ch.uppercaseChar().toString() else ch.toString()
        return cased(out, upper, allCaps)
    }

    /**
     * Транслитерация слова (или текста) с учётом регистра и соседних букв:
     * «Женя» → «Zhenya», «ЖЕНЯ» → «ZHENYA».
     * [locked] — для каждой буквы: была ли она набрана с Caps Lock;
     * [before] — символ перед словом (для правил «в начале слова»), null — начало.
     */
    fun translitWord(src: CharSequence, locked: List<Boolean>? = null, before: Char? = null): String {
        val sb = StringBuilder(src.length + 4)
        for (i in src.indices) {
            val c = src[i]
            val lower = c.lowercaseChar()
            val prev = if (i > 0) src[i - 1] else before
            val next = if (i + 1 < src.length) src[i + 1] else null
            val out = letter(
                lower,
                prev?.takeIf { it.isLetter() }?.lowercaseChar(),
                next?.takeIf { it.isLetter() }?.lowercaseChar()
            )
            if (out == null) {
                sb.append(c)
                continue
            }
            val upper = c.isUpperCase()
            val allCaps = upper && (
                (locked != null && i < locked.size && locked[i]) ||
                    (i > 0 && src[i - 1].isLetter() && src[i - 1].isUpperCase()) ||
                    (i + 1 < src.length && src[i + 1].isLetter() && src[i + 1].isUpperCase())
                )
            sb.append(cased(out, upper, allCaps))
        }
        return sb.toString()
    }

    /** Буква с учётом соседей; [prev]/[next] — соседние буквы в нижнем регистре или null. */
    private fun letter(c: Char, prev: Char?, next: Char?): String? {
        when (this) {
            GOST -> if (c == 'ц') {
                // ц → c перед i, e, y, j; иначе cz
                return if (next != null && next in GOST_SOFT) "c" else "cz"
            }
            BGN -> when (c) {
                'е' -> return if (prev == null || prev in BGN_YE_AFTER) "ye" else "e"
                'ё' -> return if (prev != null && prev in "жчшщ") "e" else "yo"
                'ь' -> return if (next == 'и' || next == 'о') "y" else ""
                else -> Unit
            }
            else -> Unit
        }
        return table[c]
    }

    private fun cased(out: String, upper: Boolean, allCaps: Boolean): String = when {
        out.isEmpty() -> out
        upper && allCaps -> out.uppercase()
        upper -> out[0].uppercaseChar() + out.substring(1)
        else -> out
    }

    companion object {
        /** Буквы русского алфавита по порядку (для редактора своей таблицы). */
        const val ALPHABET = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"

        private const val GOST_SOFT = "еёийыэюя"
        // после гласных, ъ, ь, й (и латинских гласных перед словом) «е» читается как «ye»
        private const val BGN_YE_AFTER = "аеёиоуыэюяъьйaeiouy"

        // Разговорная: привет -> privet, йогурт -> yogurt, щука -> schuka
        val colloquial: Map<Char, String> = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
            'е' to "e", 'ё' to "yo", 'ж' to "zh", 'з' to "z", 'и' to "i",
            'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
            'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
            'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch",
            'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "",
            'э' to "e", 'ю' to "yu", 'я' to "ya"
        )

        // Паспортная (ICAO): ю->iu, я->ia, х->kh, ц->ts, щ->shch, ъ->ie
        val passport: Map<Char, String> = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
            'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i",
            'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
            'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
            'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch",
            'ш' to "sh", 'щ' to "shch", 'ъ' to "ie", 'ы' to "y", 'ь' to "",
            'э' to "e", 'ю' to "iu", 'я' to "ia"
        )

        // ГОСТ 7.79-2000, система Б (без диакритики): х->x, щ->shh, ъ->``, ь->`, ы->y`, э->e`
        val gost: Map<Char, String> = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
            'е' to "e", 'ё' to "yo", 'ж' to "zh", 'з' to "z", 'и' to "i",
            'й' to "j", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
            'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
            'у' to "u", 'ф' to "f", 'х' to "x", 'ц' to "cz", 'ч' to "ch",
            'ш' to "sh", 'щ' to "shh", 'ъ' to "``", 'ы' to "y`", 'ь' to "`",
            'э' to "e`", 'ю' to "yu", 'я' to "ya"
        )

        // BGN/PCGN упрощённая (без апострофов и диакритики); контекстные правила — в letter()
        val bgn: Map<Char, String> = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
            'е' to "e", 'ё' to "yo", 'ж' to "zh", 'з' to "z", 'и' to "i",
            'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
            'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
            'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch",
            'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "",
            'э' to "e", 'ю' to "yu", 'я' to "ya"
        )

        /** Своя таблица (задаётся в настройках); буквы, которых в ней нет, — как в разговорной. */
        @Volatile var customTable: Map<Char, String> = colloquial

        /** Строки «буква=латиница». */
        fun parseCustom(s: String?): Map<Char, String> {
            val m = HashMap(colloquial)
            if (s.isNullOrEmpty()) return m
            for (line in s.split('\n')) {
                val eq = line.indexOf('=')
                if (eq < 1) continue
                val key = line.substring(0, eq).trim()
                if (key.length != 1) continue
                val c = key[0].lowercaseChar()
                if (ALPHABET.indexOf(c) < 0) continue
                m[c] = line.substring(eq + 1).trim().lowercase()
            }
            return m
        }

        fun serializeCustom(m: Map<Char, String>): String =
            ALPHABET.map { c -> "$c=${m[c] ?: colloquial[c] ?: ""}" }.joinToString("\n")

        fun byName(name: String?): TranslitMode =
            values().firstOrNull { it.name == name } ?: COLLOQUIAL
    }
}
