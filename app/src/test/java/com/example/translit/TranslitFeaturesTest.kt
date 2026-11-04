package com.example.translit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Обратный транслит, исправление раскладки, действия с выделенным, ГОСТ/BGN при наборе. */
class TranslitFeaturesTest {

    @Before
    fun reset() {
        UserHistory.resetInMemory()
        SuggestionEngine.setExtraWords("ru", emptyList())
        SuggestionEngine.setExtraWords("en", emptyList())
    }

    private fun rev(profile: FieldProfile = Harness.NO_CAPS) =
        Harness(profile = profile, lang = "ru", reverse = true)

    private val direct = Harness.NO_CAPS.copy(composing = false)

    // ------------------------------------------------------------ обратный транслит

    @Test
    fun reverseTypesCyrillic() {
        val h = rev()
        h.type("privet")
        assertEquals("привет", h.text)
        h.type(" kak dela?")
        assertEquals("привет как дела?", h.text)
    }

    @Test
    fun reverseKeepsCase() {
        val h = rev()
        h.type("Privet ")
        assertEquals("Привет ", h.text)
        h.type("SHCHUKA ")
        assertEquals("Привет ЩУКА ", h.text)
    }

    @Test
    fun reverseDictionaryChoosesReading() {
        val h = rev()
        h.type("eto otsyuda xorosho ")
        assertEquals("это отсюда хорошо ", h.text)
    }

    @Test
    fun reverseSoftSignAndUndo() {
        val h = rev()
        h.type("den ")
        assertEquals("день ", h.text)
        h.type("⌫")
        assertEquals("ден", h.text)
    }

    @Test
    fun reverseApostrophe() {
        val h = rev()
        h.type("zhizn' ob''yavlenie ")
        assertEquals("жизнь объявление ", h.text)
    }

    @Test
    fun reverseBackspaceRemovesWholeLetter() {
        val h = rev()
        h.type("sh")
        assertEquals("ш", h.text)
        h.type("⌫")
        assertEquals("", h.text)
        h.type("shc")
        h.type("⌫")
        assertEquals("ш", h.text)
    }

    @Test
    fun reverseWithoutComposing() {
        val h = rev(direct)
        h.type("privet kak dela ")
        assertEquals("привет как дела ", h.text)
    }

    @Test
    fun reverseOffersOtherReadings() {
        val h = rev()
        h.type("moi")
        val all = h.chips + h.text
        assertTrue(all.toString(), "мои" in all && "мой" in all)
    }

    @Test
    fun reverseKeepsLatinOnRequest() {
        val h = rev()
        h.type("iPhone")
        assertEquals("iPhone", h.chips.last())
        h.pick("iPhone")
        assertEquals("iPhone ", h.text)
        h.type("eto ")
        assertEquals("iPhone это ", h.text)
    }

    // ------------------------------------------------------------ исправление раскладки

    private fun en(settings: EngineSettings = EngineSettings()) =
        Harness(lang = "en", settings = settings, layoutFixMode = 1)

    @Test
    fun wrongLayoutFixedAndSwitched() {
        val h = en()
        h.type("ghbdtn ")
        assertEquals("привет ", h.text)
        assertEquals(listOf("ru"), h.switches)
        assertEquals("ru", h.conf.lang)
        h.type("как дела ")
        assertEquals("привет как дела ", h.text)
    }

    @Test
    fun layoutFixUndoSwitchesBackAndLearns() {
        val h = en()
        h.type("ghbdtn ")
        h.type("⌫")
        assertEquals("ghbdtn", h.text)
        assertEquals(listOf("ru", InputEngine.SWITCH_BACK), h.switches)
        assertEquals("en", h.conf.lang)
        h.type(" ghbdtn ")
        assertTrue(h.text, h.text.endsWith("ghbdtn "))
        assertEquals(2, h.switches.size)
    }

    @Test
    fun realWordsUntouched() {
        val h = en()
        h.type("hello world ")
        assertEquals("hello world ", h.text)
        val r = Harness(lang = "ru", layoutFixMode = 1)
        r.type("привет мир ")
        assertEquals("привет мир ", r.text)
        assertTrue(h.switches.isEmpty() && r.switches.isEmpty())
    }

    @Test
    fun shortWordsNotFixedAutomatically() {
        val h = en()
        h.type("yt")
        assertTrue(h.chips.toString(), "не" in h.chips)
        h.type(" ")
        assertEquals("yt ", h.text)
        h.type("ytn ")
        assertEquals("yt нет ", h.text)
    }

    @Test
    fun layoutFixOffShowsChip() {
        val h = en(EngineSettings(layoutFix = false))
        h.type("ghbdtn")
        assertTrue(h.chips.toString(), "привет" in h.chips)
        h.type(" ")
        assertEquals("ghbdtn ", h.text)
        assertTrue(h.switches.isEmpty())
    }

    @Test
    fun layoutChipPick() {
        val h = en(EngineSettings(layoutFix = false))
        h.type("ghbdtn")
        h.pick("привет")
        assertEquals("привет ", h.text)
        assertEquals(listOf("ru"), h.switches)
    }

    @Test
    fun russianLayoutToEnglish() {
        val h = Harness(lang = "ru", layoutFixMode = 1)
        h.type("руддщ ")
        assertEquals("hello ", h.text)
        assertEquals(listOf("en"), h.switches)
    }

    @Test
    fun translitLayoutToEnglish() {
        val h = Harness(lang = "ru", translit = TranslitMode.COLLOQUIAL, layoutFixMode = 1)
        h.type("руддщ ")
        assertEquals("hello ", h.text)
    }

    @Test
    fun fixRendersRussianAsTranslit() {
        val h = en()
        h.eng.fixRuRender = TranslitMode.COLLOQUIAL
        h.type("ghbdtn ")
        assertEquals("privet ", h.text)
    }

    @Test
    fun yavertyLayoutToEnglish() {
        val h = Harness(lang = "ru", layoutFixMode = 2)
        h.type("челло ")
        assertEquals("hello ", h.text)
    }

    // ------------------------------------------------------------ выделенный текст

    @Test
    fun selectionToLatinAndBack() {
        val h = Harness(lang = "ru")
        h.select("Привет, мир!")
        assertEquals(listOf("→ латиница", "⇄ раскладка", "→ ссылка"), h.chips)
        h.pick("→ латиница")
        assertEquals("Privet, mir!", h.text)
        assertEquals("Privet, mir!", h.selection)
        h.pick("→ кириллица")
        assertEquals("Привет, мир!", h.text)
    }

    @Test
    fun selectionPartOfText() {
        val h = Harness(lang = "ru")
        h.select("так ghbdtn всем", 4, 10)
        h.pick("⇄ раскладка")
        assertEquals("так привет всем", h.text)
        assertEquals("привет", h.selection)
    }

    @Test
    fun selectionSlug() {
        val h = Harness(lang = "ru")
        h.select("Привет, мир!")
        h.pick("→ ссылка")
        assertEquals("privet-mir", h.text)
    }

    // ------------------------------------------------------------ ГОСТ и BGN при наборе

    @Test
    fun bgnTyping() {
        val h = Harness(lang = "ru", translit = TranslitMode.BGN)
        h.type("семья подъезд ")
        assertEquals("semya podyezd ", h.text)
        h.type("ель")
        assertEquals("semya podyezd yel", h.text)
    }

    @Test
    fun gostTyping() {
        val h = Harness(lang = "ru", translit = TranslitMode.GOST)
        h.type("цирк царь ")
        assertEquals("cirk czar` ", h.text)
    }

    @Test
    fun gostTypingWithoutComposing() {
        val h = Harness(profile = direct, lang = "ru", translit = TranslitMode.GOST)
        h.type("ц")
        assertEquals("cz", h.text)
        h.type("ирк царь ")
        assertEquals("cirk czar` ", h.text)
    }

    @Test
    fun bgnTypingWithoutComposing() {
        val h = Harness(profile = direct, lang = "ru", translit = TranslitMode.BGN)
        h.type("ильич елена ")
        assertEquals("ilyich yelena ", h.text)
    }
}
