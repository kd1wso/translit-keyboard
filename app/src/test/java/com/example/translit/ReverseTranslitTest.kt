package com.example.translit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Обратный транслит: латиница → кириллица по словарю. */
class ReverseTranslitTest {

    @Before
    fun setup() {
        Dicts.ensure()
        UserHistory.resetInMemory()
    }

    /** Что окажется в поле после пробела. */
    private fun word(s: String): String {
        val live = ReverseTranslit.live(s)
        return ReverseTranslit.knownWords(s, live, "ru", 1).firstOrNull() ?: live
    }

    @Test
    fun dictionaryPicksTheRightReading() {
        val cases = linkedMapOf(
            "privet" to "привет", "eto" to "это", "xorosho" to "хорошо", "horosho" to "хорошо",
            "tsvet" to "цвет", "otsyuda" to "отсюда", "den'" to "день", "den" to "день",
            "zhenya" to "женя", "dmitriy" to "дмитрий", "moy" to "мой", "novyy" to "новый",
            "novye" to "новые", "shchuka" to "щука", "nravitsya" to "нравится", "semya" to "семья",
            "spasibo" to "спасибо", "pozhaluysta" to "пожалуйста", "chto" to "что", "vsyo" to "всё",
            "lyublyu" to "люблю", "schitat'" to "считать", "taksi" to "такси", "taxi" to "такси",
            "rayon" to "район", "yeshche" to "еще", "ego" to "его", "esli" to "если", "moi" to "мои",
            "est" to "есть", "zdravstvuyte" to "здравствуйте", "ob'yavlenie" to "объявление",
            "tatyana" to "татьяна", "statya" to "статья", "dyadya" to "дядя", "lyubov" to "любовь",
            "kak" to "как", "dela" to "дела", "segodnya" to "сегодня", "zavtra" to "завтра",
            "rabota" to "работа", "schastye" to "счастье", "mozhet" to "может", "budesh" to "будешь",
            "ochen'" to "очень", "ochen" to "очень", "tozhe" to "тоже", "ya" to "я"
        )
        val bad = ArrayList<String>()
        for ((src, want) in cases) {
            val got = word(src)
            if (got != want) bad.add("$src → $got (ждали $want)")
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun greedyReadingWithoutDictionary() {
        assertEquals("щука", ReverseTranslit.decode("shchuka"))
        assertEquals("жизнь", ReverseTranslit.decode("zhizn'"))
        assertEquals("новый", ReverseTranslit.decode("novyy"))
        assertEquals("ёлка", ReverseTranslit.decode("yolka"))
        assertEquals("кто-то", ReverseTranslit.decode("kto-to"))
    }

    @Test
    fun textConversionKeepsCaseAndPunctuation() {
        assertEquals("Привет, как дела?", ReverseTranslit.convertText("Privet, kak dela?"))
        assertEquals("ПРИВЕТ", ReverseTranslit.convertText("PRIVET"))
        assertEquals("Это 2 дня", ReverseTranslit.convertText("Eto 2 dnya"))
    }
}
