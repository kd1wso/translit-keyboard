package com.example.translit

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslitModeTest {
    private val c = TranslitMode.COLLOQUIAL
    private val p = TranslitMode.PASSPORT

    @Test
    fun colloquialWords() {
        assertEquals("privet", c.translitWord("привет"))
        assertEquals("schuka", c.translitWord("щука"))
        assertEquals("den", c.translitWord("день"))
        assertEquals("yolka", c.translitWord("ёлка"))
    }

    @Test
    fun caseOfMultiLetterSounds() {
        assertEquals("Zhenya", c.translitWord("Женя"))
        assertEquals("ZHENYA", c.translitWord("ЖЕНЯ"))
        assertEquals("Zh", c.translitWord("Ж"))
        assertEquals("ZH", c.translitWord("Ж", listOf(true)))
        assertEquals("SCH", c.translit('щ', upper = true, allCaps = true))
        assertEquals("Sch", c.translit('щ', upper = true))
    }

    @Test
    fun passport() {
        assertEquals("shchuka", p.translitWord("щука"))
        assertEquals("Iuliia", p.translitWord("Юлия"))
        assertEquals("Khabarovsk", p.translitWord("Хабаровск"))
        assertEquals("podieezd", p.translitWord("подъезд"))
    }

    @Test
    fun otherCharsPassThrough() {
        assertEquals("abc-1", c.translitWord("abc-1"))
        assertEquals("kto-to", c.translitWord("кто-то"))
    }

    @Test
    fun gost() {
        val g = TranslitMode.GOST
        assertEquals("shhuka", g.translitWord("щука"))
        assertEquals("cirk", g.translitWord("цирк"))
        assertEquals("czar`", g.translitWord("царь"))
        assertEquals("CZAR`", g.translitWord("ЦАРЬ"))
        assertEquals("ob``ekt", g.translitWord("объект"))
        assertEquals("jod", g.translitWord("йод"))
        assertEquals("Xabarovsk", g.translitWord("Хабаровск"))
        assertEquals("e`kran", g.translitWord("экран"))
        assertEquals("ry`ba", g.translitWord("рыба"))
        assertEquals("Czoj", g.translitWord("Цой"))
    }

    @Test
    fun bgn() {
        val b = TranslitMode.BGN
        assertEquals("Yelena", b.translitWord("Елена"))
        assertEquals("YELENA", b.translitWord("ЕЛЕНА"))
        assertEquals("Dmitriy", b.translitWord("Дмитрий"))
        assertEquals("Fyodor", b.translitWord("Фёдор"))
        assertEquals("Solovyov", b.translitWord("Соловьёв"))
        assertEquals("Tatyana", b.translitWord("Татьяна"))
        assertEquals("Ilyich", b.translitWord("Ильич"))
        assertEquals("podyezd", b.translitWord("подъезд"))
        assertEquals("Gorbachev", b.translitWord("Горбачёв"))
        assertEquals("Khabarovsk", b.translitWord("Хабаровск"))
        assertEquals("shchuka", b.translitWord("щука"))
        assertEquals("Tsoy", b.translitWord("Цой"))
        assertEquals("Moye", b.translitWord("Мое"))
        // «е» в начале слова зависит от символа перед ним
        assertEquals("ye", b.translitWord("е", before = ' '))
        assertEquals("e", b.translitWord("е", before = 'л'))
    }

    @Test
    fun customTable() {
        val m = TranslitMode.parseCustom("щ=shh\nх=kh\nй = J\nq=z\nя=")
        assertEquals("shh", m['щ'])
        assertEquals("kh", m['х'])
        assertEquals("j", m['й'])
        assertEquals("", m['я'])
        assertEquals("a", m['а'])
        assertEquals(m, TranslitMode.parseCustom(TranslitMode.serializeCustom(m)))
        val before = TranslitMode.customTable
        TranslitMode.customTable = m
        try {
            assertEquals("Shhuka", TranslitMode.CUSTOM.translitWord("Щука"))
            assertEquals("SHHUKA", TranslitMode.CUSTOM.translitWord("ЩУКА"))
            assertEquals("jod", TranslitMode.CUSTOM.translitWord("йод"))
        } finally {
            TranslitMode.customTable = before
        }
    }
}
