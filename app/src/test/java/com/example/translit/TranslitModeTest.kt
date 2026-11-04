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
}
