package com.example.translit

enum class TranslitMode {
    COLLOQUIAL, PASSPORT;

    private val table: Map<Char, String>
        get() = if (this == COLLOQUIAL) colloquial else passport

    /**
     * Транслитерация одной буквы.
     * [upper] — буква заглавная; [allCaps] — слово набирается целиком заглавными
     * (Caps Lock или соседние буквы заглавные): «Ж» → «ZH», а не «Zh».
     */
    fun translit(ch: Char, upper: Boolean, allCaps: Boolean = false): String {
        val out = table[ch.lowercaseChar()]
            ?: return if (upper) ch.uppercaseChar().toString() else ch.toString()
        return when {
            out.isEmpty() -> out
            upper && allCaps -> out.uppercase()
            upper -> out[0].uppercaseChar() + out.substring(1)
            else -> out
        }
    }

    /**
     * Транслитерация слова с учётом регистра: «Женя» → «Zhenya», «ЖЕНЯ» → «ZHENYA».
     * [locked] — для каждой буквы: была ли она набрана с Caps Lock.
     */
    fun translitWord(src: CharSequence, locked: List<Boolean>? = null): String {
        val sb = StringBuilder(src.length + 4)
        for (i in src.indices) {
            val c = src[i]
            val upper = c.isUpperCase()
            val allCaps = upper && (
                (locked != null && i < locked.size && locked[i]) ||
                    (i > 0 && src[i - 1].isLetter() && src[i - 1].isUpperCase()) ||
                    (i + 1 < src.length && src[i + 1].isLetter() && src[i + 1].isUpperCase())
                )
            sb.append(translit(c, upper, allCaps))
        }
        return sb.toString()
    }

    companion object {
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
    }
}
